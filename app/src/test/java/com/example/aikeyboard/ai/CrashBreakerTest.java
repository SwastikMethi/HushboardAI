// A model that takes the process down while loading must be found out at the next start,
// exactly once, so it can be switched off instead of crashing the keyboard in a loop.
package com.example.aikeyboard.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Set;

public class CrashBreakerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private CrashBreaker newProcess() {
        return new CrashBreaker(new File(temp.getRoot(), "breaker"));
    }

    @Test
    public void aCleanStartReportsNothing() {
        assertEquals(Set.of(), newProcess().crashedLastTime());
    }

    @Test
    public void aLoadThatFinishedLeavesNoTrace() {
        CrashBreaker breaker = newProcess();
        breaker.loading("emo");
        breaker.loaded("emo");
        assertEquals(Set.of(), newProcess().crashedLastTime());
    }

    @Test
    public void aLoadThatNeverFinishedIsReportedByTheNextProcess() {
        CrashBreaker dying = newProcess();
        dying.loading("toxic");
        dying.loading("emo");
        dying.loaded("emo");
        // ...the process dies here, inside the native load of "toxic".

        assertEquals(Set.of("toxic"), newProcess().crashedLastTime());
    }

    @Test
    public void aCrashIsReportedOnlyOnce() {
        newProcess().loading("toxic");
        newProcess().crashedLastTime();
        assertEquals(Set.of(), newProcess().crashedLastTime());
    }
}
