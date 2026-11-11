package dev.peanuts.build;

/** Progress of a build back to the game sheet; called on the main thread. step: import | prepare | companion | done; percent: 0..100 over the whole build */
public interface BuildListener {
    void progress(String step, int percent, String detail);

    void done(boolean ok, String message);
}
