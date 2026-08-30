/*
 * This file is part of Princeps.
 *
 * Princeps is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package princeps.process;

import org.junit.Test;
import princeps.pathing.calc.AStarPathFinder;
import princeps.pathing.calc.SearchBudget;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The node budget of one path search, from the owner's build algorithm v0.4.
 *
 * <p>S4 gives lane A 5000 nodes and accepts nothing but a complete route; S5 gives lane B 50000. Both numbers reach
 * the searcher through {@link SearchBudget}, so the two lanes are only really two lanes if a budget means what it
 * says. The three properties tested here are the ones the rest of the algorithm leans on: {@link
 * SearchBudget#UNLIMITED} really is unbounded (it is what every pre-v0.4 call site passes, and passing it must
 * change nothing), a budget of zero or less is rejected rather than silently accepted as "search nothing", and the
 * name survives into {@code toString} so a run says WHICH budget ended a search.
 *
 * <p>Tested directly — {@code SearchBudget} is a public type with no world access, so nothing has to be mirrored
 * here and no Minecraft bootstrap is needed.
 */
public class SearchBudgetTest {

    /** The two numbers the spec names, kept here so a change to either shows up as a test change. */
    private static final int LANE_A_NODES = 5_000;
    private static final int LANE_B_NODES = 50_000;

    // ----------------------------------------------------------------------------------------------------------
    // UNLIMITED
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void unlimitedIsUnbounded() {
        assertTrue(SearchBudget.UNLIMITED.isUnlimited());
        assertEquals(Integer.MAX_VALUE, SearchBudget.UNLIMITED.maxNodes());
    }

    /**
     * "Unbounded" has to mean it cannot be reached in practice, not merely that it is large. The searcher compares
     * an expanded-node count against {@code maxNodes()}, and that counter is an {@code int}: any ceiling below
     * {@code Integer.MAX_VALUE} is a ceiling a long search could hit.
     */
    @Test
    public void unlimitedCannotBeReachedByAnyNodeCount() {
        int ceiling = SearchBudget.UNLIMITED.maxNodes();
        assertFalse(240_000 >= ceiling);        // the largest single search actually measured (etz-basalt run)
        assertFalse(5_000_000 >= ceiling);      // its movement-consideration count, an order of magnitude above
        assertFalse(Integer.MAX_VALUE - 1 >= ceiling);
    }

    @Test
    public void unlimitedIsASingleSharedConstant() {
        assertSame(SearchBudget.UNLIMITED, SearchBudget.UNLIMITED);
        assertTrue(SearchBudget.UNLIMITED.isUnlimited());
    }

    // ----------------------------------------------------------------------------------------------------------
    // ofNodes
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void ofNodesKeepsItsCeilingAndItsName() {
        SearchBudget laneA = SearchBudget.ofNodes(LANE_A_NODES, "lane A");
        assertEquals(LANE_A_NODES, laneA.maxNodes());
        assertEquals("lane A", laneA.name());
        assertFalse(laneA.isUnlimited());

        SearchBudget laneB = SearchBudget.ofNodes(LANE_B_NODES, "lane B");
        assertEquals(LANE_B_NODES, laneB.maxNodes());
        assertEquals("lane B", laneB.name());
        assertFalse(laneB.isUnlimited());
    }

    @Test
    public void ofNodesRejectsZero() {
        try {
            SearchBudget.ofNodes(0, "lane A");
            fail("a budget of 0 nodes must be rejected: a search that may expand nothing can only ever fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("0"));
        }
    }

    @Test
    public void ofNodesRejectsNegativeValues() {
        for (int nodes : new int[]{-1, -5_000, Integer.MIN_VALUE}) {
            try {
                SearchBudget.ofNodes(nodes, "nonsense");
                fail("a budget of " + nodes + " nodes must be rejected");
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    /** One node is the smallest budget that can find anything, and it is legal. The boundary sits between 0 and 1. */
    @Test
    public void ofNodesAcceptsOne() {
        SearchBudget smallest = SearchBudget.ofNodes(1, "one");
        assertEquals(1, smallest.maxNodes());
        assertFalse(smallest.isUnlimited());
    }

    /**
     * {@code Integer.MAX_VALUE} is the sentinel for "unbounded", so a budget explicitly asking for that many nodes
     * reports itself as unlimited and prints as unlimited — its name is dropped. Documented here because it is a
     * surprise waiting for anyone who computes a ceiling and lands on the sentinel.
     */
    @Test
    public void aBudgetOfMaxValueNodesIsIndistinguishableFromUnlimited() {
        SearchBudget enormous = SearchBudget.ofNodes(Integer.MAX_VALUE, "enormous");
        assertTrue(enormous.isUnlimited());
        assertEquals(SearchBudget.UNLIMITED.toString(), enormous.toString());
        assertEquals("enormous", enormous.name());   // the name is kept, it just does not reach toString
    }

    // ----------------------------------------------------------------------------------------------------------
    // toString
    // ----------------------------------------------------------------------------------------------------------

    @Test
    public void toStringOfUnlimitedIsReadable() {
        assertEquals("budget[unlimited]", SearchBudget.UNLIMITED.toString());
    }

    @Test
    public void toStringOfABoundedBudgetNamesItAndItsCeiling() {
        assertEquals("budget[lane A, 5000 nodes]", SearchBudget.ofNodes(LANE_A_NODES, "lane A").toString());
        assertEquals("budget[lane B, 50000 nodes]", SearchBudget.ofNodes(LANE_B_NODES, "lane B").toString());
        assertEquals("budget[one, 1 nodes]", SearchBudget.ofNodes(1, "one").toString());
    }

    /** Whatever the exact format, a log line must contain both halves of the answer: which budget, and how big. */
    @Test
    public void toStringCarriesBothTheNameAndTheNodeCount() {
        String printed = SearchBudget.ofNodes(1234, "recovery").toString();
        assertTrue(printed, printed.contains("recovery"));
        assertTrue(printed, printed.contains("1234"));
    }

    // ----------------------------------------------------------------------------------------------------------
    // the budget is mandatory, not optional
    // ----------------------------------------------------------------------------------------------------------

    /**
     * A budget nobody is forced to pass is a budget the next call site forgets, silently. The searcher therefore
     * takes it as a constructor parameter and offers no overload without one — this guards that, because adding a
     * convenience overload later would undo the whole point of the type.
     */
    @Test
    public void everyPublicSearcherConstructorDemandsABudget() {
        Constructor<?>[] constructors = AStarPathFinder.class.getConstructors();
        assertTrue("AStarPathFinder should expose at least one public constructor", constructors.length > 0);
        for (Constructor<?> constructor : constructors) {
            boolean takesBudget = false;
            for (Class<?> parameter : constructor.getParameterTypes()) {
                if (parameter == SearchBudget.class) {
                    takesBudget = true;
                    break;
                }
            }
            assertTrue("this constructor lets a caller start a search with no budget at all: " + constructor,
                    takesBudget);
        }
    }
}
