package com.cendreal.accessory.paper.hook.auraskills;

import dev.aurelium.auraskills.api.item.ItemContext;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.stat.CustomStat;

public class AccessoryStats {

    //初始化加载State
    public static final CustomStat CUSTOM_STAT = CustomStat
            .builder(NamespacedId.of("accessory", "custom_stat"))
                    .trait(AccessoryTraits.HEAL_REGENERATION, 0.001)
                    .trait(AccessoryTraits.DEFENCE, 0.001)
                    .trait(AccessoryTraits.HEALTH, 0.001)
                    .displayName("custom_stat")
                    .description("this used to load the stats automaticlly.")
                    .color("<green>")
                    .symbol("")
                    .item(ItemContext.builder()
                            .material("lime_stained_glass_pane")
                            .group("lower")
                            // A group defined in AuraSkills/menus/stats.yml
                            .order(2)
                            .build())

                    .build();
}