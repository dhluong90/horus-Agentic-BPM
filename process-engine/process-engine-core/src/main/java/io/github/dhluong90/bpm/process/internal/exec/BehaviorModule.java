package io.github.dhluong90.bpm.process.internal.exec;

/** A group of behaviours and compile steps owned by one stream. */
public interface BehaviorModule {

    /** @param registry the registry to add to */
    void register(BehaviorRegistry registry);
}
