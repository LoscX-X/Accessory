package com.cendreal.accessory.paper.hook.mythicmobs;

import com.cendreal.accessory.core.skill.*;
import com.cendreal.accessory.paper.listener.*;
import com.cendreal.accessory.api.AccessorySkillSource;
import com.cendreal.accessory.core.skill.trigger.SkillTrigger;
import io.lumine.mythic.bukkit.*;
import org.bukkit.entity.*;
import com.cendreal.accessory.api.extension.AccessorySkillCast;
import com.cendreal.accessory.api.extension.AccessorySkillExecutor;

/** The only skill executor that links MythicMobs classes. */
public final class MythicSkillBackend implements AccessorySkillExecutor {
    public static final String ACCESSORY_CAST_META = "blacc:accessory-cast";
    public String id() { return "mythic"; }
    @Override public boolean cast(Player owner, AccessorySkillCast cast) {
        Entity target = cast.target();
        var source = cast.source();
        return MythicBukkit.inst().getAPIHelper().castSkill(owner, cast.skill(), metadata -> {
            metadata.setMetadata(ACCESSORY_CAST_META, true);
            if (source != null) metadata.setMetadata(AccessorySkillSource.METADATA_KEY, source);
            if (target != null) metadata.setEntityTarget(BukkitAdapter.adapt(target));
            Entity trigger = cast.trigger() == null ? target : cast.trigger();
            if (trigger != null) metadata.setTrigger(BukkitAdapter.adapt(trigger));
            if (cast.afterDeath()) {
                metadata.setExecuteAfterDeath(true);
                if (cast.forceSync()) metadata.setIsAsync(false);
            }
        });
    }
}
