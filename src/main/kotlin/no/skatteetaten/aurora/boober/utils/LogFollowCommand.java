package no.skatteetaten.aurora.boober.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * Create a Log command that enables the follow option: git log --follow -- < path >
 * User: OneWorld
 * Example for usage: ArrayList<RevCommit> commits =  new  LogFollowCommand(repo,"src/com/mycompany/myfile.java")
 * .call();
 */
public class LogFollowCommand {

    private final Repository repository;
    private String path;
    private Git git;

    /**
     * Create a Log command that enables the follow option: git log --follow -- < path >
     *
     * @param repository
     * @param path
     */
    public LogFollowCommand(Repository repository, String path) {
        this.repository = repository;
        this.path = path;
    }

    /**
     * Returns the result of a git log --follow -- < path >
     *
     * @return
     * @throws IOException
     * @throws MissingObjectException
     * @throws GitAPIException
     */
    public ArrayList<RevCommit> call() throws IOException, GitAPIException {
        ArrayList<RevCommit> commits = new ArrayList<RevCommit>();
        git = new Git(repository);
        RevCommit start = null;
        do {
            Iterable<RevCommit> log = git.log().addPath(path).call();
            for (RevCommit commit : log) {
                if (commits.contains(commit)) {
                    start = null;
                } else {
                    start = commit;
                    commits.add(commit);
                }
            }
            if (start == null) {
                return commits;
            }
        }
        while ((path = getRenamedPath(start)) != null);

        return commits;
    }

    /**
     * Checks for renames in history of a certain file. Returns null, if no rename was found.
     * Can take some seconds, especially if nothing is found... Here might be some tweaking necessary or the
     * LogFollowCommand must be run in a thread.
     *
     * @param start
     * @return String or null
     * @throws IOException
     * @throws MissingObjectException
     * @throws GitAPIException
     */
    private String getRenamedPath(RevCommit start) throws IOException, GitAPIException {
        Iterable<RevCommit> allCommitsLater = git.log().add(start).call();
        for (RevCommit commit : allCommitsLater) {

            TreeWalk tw = new TreeWalk(repository);
            tw.addTree(commit.getTree());
            tw.addTree(start.getTree());
            tw.setRecursive(true);
            RenameDetector rd = new RenameDetector(repository);
            rd.addAll(DiffEntry.scan(tw));
            List<DiffEntry> files = rd.compute();
            for (DiffEntry diffEntry : files) {
                if ((diffEntry.getChangeType() == DiffEntry.ChangeType.RENAME
                    || diffEntry.getChangeType() == DiffEntry.ChangeType.COPY) && diffEntry.getNewPath()
                    .contains(path)) {
                    System.out.println("Found: " + diffEntry.toString() + " return " + diffEntry.getOldPath());
                    return diffEntry.getOldPath();
                }
            }
        }
        return null;
    }
}