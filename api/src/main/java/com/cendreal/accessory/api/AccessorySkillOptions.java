package com.cendreal.accessory.api;

/** Read-only view of the current skill settings; reflects the latest successful reload. */
public interface AccessorySkillOptions {
    boolean enabled();
    boolean resetCooldownsOnQuit();
}