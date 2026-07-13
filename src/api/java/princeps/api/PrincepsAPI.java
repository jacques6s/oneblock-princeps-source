/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princeps is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princeps.  If not, see <https://www.gnu.org/licenses/>.
 */

package princeps.api;

import princeps.api.utils.SettingsUtil;

/**
 * Exposes the {@link IPrincepsProvider} instance and the {@link Settings} instance for API usage.
 *
 * @author Brady
 * @since 9/23/2018
 */
public final class PrincepsAPI {

    private static final IPrincepsProvider provider;
    private static final Settings settings;

    static {
        settings = new Settings();
        SettingsUtil.readAndApply(settings, SettingsUtil.SETTINGS_DEFAULT_NAME);
        // Ground navigation is tuned around the standard 9 deg/tick aim curve. Elytra steering bypasses this
        // curve entirely and uses its dedicated flight/landing/agile smoothing settings instead.
        settings.humanizedLookAimCurveMode.value = 1;

        try {
            provider = (IPrincepsProvider) Class.forName("princeps.PrincepsProvider").newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static IPrincepsProvider getProvider() {
        return PrincepsAPI.provider;
    }

    public static Settings getSettings() {
        return PrincepsAPI.settings;
    }
}
