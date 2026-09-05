#!/usr/bin/env python3
"""Compare independently rebuilt archives and conservatively check the compiled API surface.

Uses only the Python standard library. Does not load or execute classes from any jar.
"""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def archive(path):
    data = path.read_bytes()
    with zipfile.ZipFile(path) as jar:
        files = [entry for entry in jar.infolist() if not entry.is_dir()]
        contents = {entry.filename: jar.read(entry) for entry in files}
        names = [entry.filename for entry in files]
        metadata = {
            entry.filename: {"time": entry.date_time, "compression": entry.compress_type,
                             "crc32": entry.CRC, "size": entry.file_size}
            for entry in files
        }
    return {"path": str(path.resolve()), "sha256": sha256(data), "size": len(data),
            "entries": len(names), "class_entries": sum(name.endswith('.class') for name in names),
            "duplicates": sorted({name for name in names if names.count(name) > 1}),
            "order": names, "metadata": metadata, "contents": contents}


def compare(a, b):
    common = a['contents'].keys() & b['contents'].keys()
    return {"byte_identical": a['sha256'] == b['sha256'],
            "only_left": sorted(a['contents'].keys() - b['contents'].keys()),
            "only_right": sorted(b['contents'].keys() - a['contents'].keys()),
            "different_contents": sorted(name for name in common if a['contents'][name] != b['contents'][name]),
            "different_zip_metadata": sorted(name for name in common if a['metadata'][name] != b['metadata'][name]),
            "same_entry_order": a['order'] == b['order']}


class Reader:
    def __init__(self, data):
        self.data, self.position = data, 0

    def read(self, count):
        value = self.data[self.position:self.position + count]
        if len(value) != count:
            raise ValueError('truncated class file')
        self.position += count
        return value

    def number(self, count):
        return int.from_bytes(self.read(count), 'big')


def class_surface(data):
    r = Reader(data)
    if r.number(4) != 0xCAFEBABE:
        raise ValueError('not a class file')
    minor, major = r.number(2), r.number(2)
    pool = [None] * r.number(2)
    slot = 1
    while slot < len(pool):
        tag = r.number(1)
        if tag == 1:
            value = r.read(r.number(2)).decode('utf-8', errors='replace')
        elif tag in (3, 4):
            value = r.read(4).hex()
        elif tag in (5, 6):
            value = r.read(8).hex()
        elif tag in (7, 8, 16, 19, 20):
            value = r.number(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (r.number(2), r.number(2))
        elif tag == 15:
            value = (r.number(1), r.number(2))
        else:
            raise ValueError(f'unknown constant pool tag {tag}')
        pool[slot] = (tag, value)
        slot += 2 if tag in (5, 6) else 1

    def utf(index):
        return pool[index][1]

    def type_name(index):
        return utf(pool[index][1]) if index else None

    access, this, parent = r.number(2), r.number(2), r.number(2)
    interfaces = [type_name(r.number(2)) for _ in range(r.number(2))]

    def attributes():
        return {utf(r.number(2)): r.read(r.number(4)) for _ in range(r.number(2))}

    def members():
        result = {}
        for _ in range(r.number(2)):
            flags, name, descriptor = r.number(2), utf(r.number(2)), utf(r.number(2))
            attrs = attributes()
            if flags & (0x0001 | 0x0004):
                item = {"access": flags}
                if 'ConstantValue' in attrs:
                    index = int.from_bytes(attrs['ConstantValue'], 'big')
                    item['constant'] = pool[index]
                    if pool[index][0] == 8:
                        item['constant'] = ('string', utf(pool[index][1]))
                result[name + descriptor] = item
        return result

    fields, methods = members(), members()
    attributes()
    if r.position != len(data):
        raise ValueError('unconsumed class-file bytes')
    return {"name": type_name(this), "major": major, "access": access,
            "parent": type_name(parent), "interfaces": interfaces, "fields": fields, "methods": methods}


def api_compatibility(old, new):
    old_classes = {name: class_surface(data) for name, data in old['contents'].items() if name.endswith('.class')}
    new_classes = {name: class_surface(data) for name, data in new['contents'].items() if name.endswith('.class')}
    missing_classes = sorted(old_classes.keys() - new_classes.keys())
    missing_members, changed_flags, added_abstract, changed_constants, changed_parents = [], [], [], [], []
    for name in old_classes.keys() & new_classes.keys():
        before, after = old_classes[name], new_classes[name]
        if (before['access'] ^ after['access']) & 0x0611:
            changed_flags.append(name + ':class')
        if before['parent'] != after['parent'] or not set(before['interfaces']) <= set(after['interfaces']):
            changed_parents.append(name)
        for kind in ('fields', 'methods'):
            a, b = before[kind], after[kind]
            missing_members += [name + ':' + kind + ':' + member for member in a.keys() - b.keys()]
            for member in a.keys() & b.keys():
                # static, final, abstract, visibility; conservative: report any change for review.
                if (a[member]['access'] ^ b[member]['access']) & 0x041F:
                    changed_flags.append(name + ':' + member)
                if a[member].get('constant') != b[member].get('constant'):
                    changed_constants.append(name + ':' + member)
            if kind == 'methods':
                added_abstract += [name + ':' + member for member in b.keys() - a.keys()
                                  if b[member]['access'] & 0x0400]
    return {"scope": "declared public/protected JVM descriptors, flags, parents and constants; no behavioral guarantee",
            "baseline_classes": len(old_classes), "candidate_classes": len(new_classes),
            "missing_classes": missing_classes, "added_classes": sorted(new_classes.keys() - old_classes.keys()),
            "missing_members": sorted(missing_members), "changed_linkage_flags": sorted(changed_flags),
            "added_abstract_methods": sorted(added_abstract), "changed_constants": sorted(changed_constants),
            "changed_parents": sorted(changed_parents)}


def summary(item):
    result = {key: item[key] for key in ('path', 'sha256', 'size', 'entries', 'class_entries', 'duplicates')}
    for name in ('fabric.mod.json', 'META-INF/MANIFEST.MF'):
        if name in item['contents']:
            result[name] = item['contents'][name].decode('utf-8')
    result['nested_jars'] = {name: sha256(data) for name, data in item['contents'].items() if name.endswith('.jar')}
    result['licenses'] = sorted(name for name in item['contents'] if 'LICENSE' in name.upper() or 'NOTICE' in name.upper())
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('first', type=Path)
    parser.add_argument('second', type=Path)
    parser.add_argument('--baseline-api', type=Path)
    parser.add_argument('--baseline-runtime', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    names = ('princeps-1.17.0.jar', 'princeps-1.17.0-api.jar')
    pairs = {name: (archive(args.first / name), archive(args.second / name)) for name in names}
    report = {"builds": {name: {"first": summary(a), "second": summary(b), "comparison": compare(a, b)}
                         for name, (a, b) in pairs.items()}}
    if args.baseline_api:
        report['api_compatibility'] = api_compatibility(archive(args.baseline_api), pairs[names[1]][0])
    if args.baseline_runtime:
        report['runtime_change_from_current_shipped'] = compare(archive(args.baseline_runtime), pairs[names[0]][0])
    # The API facade must be exactly the same classes as those present in the full runtime jar.
    runtime, api = pairs[names[0]][0], pairs[names[1]][0]
    report['api_runtime_class_mismatches'] = sorted(name for name, data in api['contents'].items()
                                                  if name.endswith('.class') and runtime['contents'].get(name) != data)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({name: {"sha256": a['sha256'], "byte_identical": a['sha256'] == b['sha256'],
                            "entries": a['entries']} for name, (a, b) in pairs.items()}, indent=2))
    if not all(a['sha256'] == b['sha256'] for a, b in pairs.values()) or report['api_runtime_class_mismatches']:
        raise SystemExit('archive reproducibility or runtime/API coherence failed')
    if args.baseline_api and any(report['api_compatibility'][key] for key in (
            'missing_classes', 'missing_members', 'changed_linkage_flags', 'added_abstract_methods',
            'changed_constants', 'changed_parents')):
        raise SystemExit('public API differences require review')


if __name__ == '__main__':
    main()
