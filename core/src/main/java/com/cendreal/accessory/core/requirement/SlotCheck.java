package com.cendreal.accessory.core.requirement;
import com.cendreal.accessory.core.configuration.lang.Lang;

public record SlotCheck(Result result, Denial denial, String minimum) {
    public enum Result { MET, UNKNOWN, NOT_MET }
    public enum Denial { FRAME, PERMISSION, TYPE, LORE, LEVEL, CLASS }
    public static final SlotCheck PASS = new SlotCheck(Result.MET, null, "");
    public static SlotCheck denied(Denial reason) { return new SlotCheck(Result.NOT_MET, reason, ""); }
    public static SlotCheck unknown(Denial reason) { return new SlotCheck(Result.UNKNOWN, reason, ""); }
    public boolean returnsItem() { return result == Result.NOT_MET && (denial == Denial.LEVEL || denial == Denial.CLASS); }
    public String message() {
        if (result == Result.UNKNOWN) return "Slot_requirement_unavailable";
        if (denial == Denial.LEVEL) return "Slot_level_not_met";
        if (denial == Denial.PERMISSION) return "Slot_no_permission";
        if (denial == Denial.CLASS) return "Slot_class_not_met";
        return "Item_not_match";
    }
    public void tell(org.bukkit.entity.Player viewer, Lang lang) {
        viewer.sendMessage(lang.langComponent(viewer, message(), java.util.Map.of("min", minimum)));
    }
    /** For an AND condition, keep the first denial of the highest certainty. */
    public SlotCheck and(SlotCheck other) { return other.result.ordinal() > result.ordinal() ? other : this; }
}
