package com.google.gerrit.pgm.wandisco;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Die;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.Project.NameKey;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.Sequences;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.RepoSequence;
import com.google.inject.Inject;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static com.google.gerrit.server.notedb.RepoSequence.SEQUENCE_TUPLE_DELIMITER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

/**
 * Manage the reverting of specified Gerrit sequences in their corresponding
 * projects.
 *
 * All-Projects -> refs/sequences/changes
 * All-Users -> refs/sequences/accounts
 * All-Users -> refs/sequences/groups
 */
public class RevertRepoSequence implements UninstallStep {

    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private static final String SEQUENCE_READ_FAILURE = "Failed to read sequence for project %s, ref %s.";

    private static final String SEQUENCE_UPDATE_FAILURE = "Sequence update for project %s, ref %s failed. This action is " +
                                                          "idempotent, so it is safe to turn on debug logging and try" +
                                                          " again.";

    private final GitRepositoryManager repoManager;

    private final Map<NameKey, Set<String>> sequences;

    @Inject
    public RevertRepoSequence(GitRepositoryManager repoManager,
                              AllProjectsName allProjectsName,
                              AllUsersName allUsersName) {
        this.repoManager = repoManager;
        this.sequences = new HashMap<>();

        Set<String> allProjectsSequences = new HashSet<>();
        allProjectsSequences.add(Sequences.NAME_CHANGES);

        Set<String> allUsersSequences = new HashSet<>();
        allUsersSequences.add(Sequences.NAME_ACCOUNTS);
        allUsersSequences.add(Sequences.NAME_GROUPS);

        this.sequences.put(allProjectsName, allProjectsSequences);
        this.sequences.put(allUsersName, allUsersSequences);
    }

    @Override
    public void run() throws Die {
        logger.atInfo().log("Checking Gerrit sequences to revert the format change from " +
                            "{NODE_ID:SEQUENCE_INT} to {SEQUENCE_INT}");
        sequences.forEach((project, sequences) -> {
            sequences.forEach(sequence -> updateSequence(project, sequence));
        });
    }

    /**
     * Check and optionally update sequence if WANdisco tuple form is found,
     * otherwise return.
     *
     * @param project project name
     * @param sequenceName sequence name
     */
    @VisibleForTesting
    void updateSequence(Project.NameKey project, String sequenceName) {
        final String refName = RefNames.REFS_SEQUENCES + sequenceName;
        logger.atInfo().log("Checking %s in %s", refName, project.get());

        final String currentSequence;
        try (Repository repo = repoManager.openRepository(project);
             RevWalk rw = new RevWalk(repo)) {
            ObjectId id = repo.exactRef(refName).getObjectId();
            currentSequence = new String(rw.getObjectReader().open(id).getCachedBytes(), UTF_8);

            if (currentSequence.contains(SEQUENCE_TUPLE_DELIMITER)) {
                logger.atInfo().log("{NODE_ID:SEQUENCE_INT} format found.");
                doSequenceUpdate(repo, refName, project, currentSequence);
            }
        } catch (IOException e) {
            throw new Die(String.format(SEQUENCE_READ_FAILURE,
                                        project.get(), refName), e);
        }
    }

    /**
     * Perform sequence update on provided project/sequence name.
     *
     * @param repo open repository
     * @param refName targeted sequence ref (refs/sequences/{sequenceName})
     * @param project project name
     * @param currentSequence current sequence tuple
     */
    private void doSequenceUpdate(Repository repo, String refName, Project.NameKey project, String currentSequence) {
        final String newSequenceNumber = decodeSequence(currentSequence);
        logger.atInfo().log("Updating current sequence tuple %s to new sequence integer %s",
                            currentSequence, newSequenceNumber);

        try (ObjectInserter ins = repo.newObjectInserter()) {
                ObjectId newId = ins.insert(OBJ_BLOB, newSequenceNumber.getBytes(UTF_8));
                ins.flush();
                RefUpdate ru = repo.updateRef(refName);
                ru.setNewObjectId(newId);
                RefUpdate.Result result = ru.forceUpdate();

                switch(result) {
                    case NEW:
                    case FORCED:
                    case FAST_FORWARD:
                        break;
                    default:
                        throw new Die(String.format(SEQUENCE_UPDATE_FAILURE,
                                                    project.get(), refName));
                }
            } catch (IOException e) {
                throw new Die(String.format(SEQUENCE_UPDATE_FAILURE,
                                            project.get(), refName), e);
            }
    }

    /**
     * Decode sequence tuple back to integer form.
     * Sequence number will be rolled on to next integer so no overlap occurs.
     *
     * @param currentSequence current sequence tuple
     * @return new sequence number
     */
    private String decodeSequence(String currentSequence) {
        int newSequence = RepoSequence.decodeSequenceString(currentSequence);
        newSequence++;
        return Integer.toString(newSequence);
    }
}
