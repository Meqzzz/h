package dev.swordsmp;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;

public enum SwordType {
    INFERNAL("Infernal Sword", "Flame Dash", "Infernal Eruption", "Hellstorm", Color.ORANGE),
    SHADOW("Shadow Sword", "Shadow Step", "Phantom Strike", "Shadow Domain", Color.PURPLE),
    THUNDER("Thunder Sword", "Lightning Dash", "Thunder Chain", "Divine Thunder", Color.YELLOW),
    EARTH("Earth Sword", "Seismic Slam", "Earth Spikes", "Titan's Wrath", Color.GREEN),
    FROST("Frost Sword", "Frost Slash", "Frozen Prison", "Absolute Zero", Color.AQUA),
    VITALITY("Vitality Sword", "Life Steal", "Nature's Blessing", "Divine Bloom", Color.LIME);

    public final String display, first, second, ultimate;
    public final Color color;
    SwordType(String display, String first, String second, String ultimate, Color color) {
        this.display = display; this.first = first; this.second = second;
        this.ultimate = ultimate; this.color = color;
    }
    public String id() { return name().toLowerCase(java.util.Locale.ROOT); }
    public static SwordType find(String id) {
        try { return valueOf(id.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
