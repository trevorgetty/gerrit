package com.google.gerrit.pgm.wandisco;

import com.google.gerrit.common.Die;
import com.google.gerrit.gerritconsoleapi.bindings.ProjectLoader;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import org.eclipse.jgit.lib.Config;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EnsureProjectsNotReplicatedTest {

    @Mock
    private ProjectLoader projectLoader;

    @Mock
    private Config config;

    @Mock
    private ProjectLoader.Factory factory;

    private AllProjectsName allProjectsName;

    private AllUsersName allUsersName;

    private EnsureProjectsNotReplicated ensureProjectsNotReplicated;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        allProjectsName = new AllProjectsName("All-projects");
        allUsersName = new AllUsersName("All-Users");

        when(factory.create(any(Project.NameKey.class))).thenReturn(projectLoader);
        ensureProjectsNotReplicated = new EnsureProjectsNotReplicated(factory, allProjectsName, allUsersName);
    }

    private void initMocks() throws Exception {
        when(projectLoader.getProjectName()).thenReturn(allProjectsName.get())
                                            .thenReturn(allUsersName.get())
                                            .thenReturn(allProjectsName.get())
                                            .thenReturn(allUsersName.get());
        when(projectLoader.getProjectConfigSnapshot()).thenReturn(config);
    }

    @Test
    public void ifNotReplicatedShouldNotThrow() throws Exception {
        initMocks();
        when(config.getBoolean("core", "replicated", true)).thenReturn(false);

        try {
            ensureProjectsNotReplicated.run();
        } catch (Exception e) {
            fail("Shouldn't have thrown an exception");
        }

        verify(config, times(2)).getBoolean("core", "replicated", true);
    }

    @Test
    public void ifReplicatedShouldThrowForOne() throws Exception {
        initMocks();
        when(config.getBoolean("core", "replicated", true))
                .thenReturn(false)
                .thenReturn(true);

        try {
            ensureProjectsNotReplicated.run();
            fail("Should have thrown an exception.");
        } catch (Exception e) {
            assertTrue(e instanceof Die);
        }

        verify(config, times(2)).getBoolean("core", "replicated", true);
    }

    @Test
    public void ifReplicatedShouldThrowForBoth() throws Exception {
        initMocks();
        when(config.getBoolean("core", "replicated", true))
                .thenReturn(true)
                .thenReturn(true);

        try {
            ensureProjectsNotReplicated.run();
            fail("Should have thrown an exception.");
        } catch (Exception e) {
            assertTrue(e instanceof Die);
        }

        verify(config, times(2)).getBoolean("core", "replicated", true);
    }
}
