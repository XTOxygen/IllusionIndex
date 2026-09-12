package com.endwithme.illusionindex;

import java.io.File;
import net.minecraftforge.common.config.Configuration;
import org.lwjgl.input.Keyboard;

/**
 * All settings of IllusionIndex. Persisted to {@code config/IllusionIndex.cfg}
 * through the Forge {@link Configuration} system. Every field has a matching
 * config property; {@link #save()} writes pending changes to disk so that all
 * settings (including key bindings) survive a restart.
 *
 * <p>The GUI mutates the public fields directly (changes apply immediately at
 * render time) and then calls {@link #save()}.
 *
 * @author EndWithMe
 */
public final class Config
{
    public static final String CAT_GENERAL   = "general";
    public static final String CAT_RENDER    = "render";
    public static final String CAT_AFTERIMG  = "afterimage";

    public static final int DEFAULT_KEY_OPEN_GUI = Keyboard.KEY_I; // 23

    /** Master switch of the whole mod. */
    public boolean enabled = true;

    /** Print "[IIndex]:Spotted an invisible player! ..." chat alerts. */
    public boolean spotChatEnabled = true;

    /** Attack range used for the spot alert (blocks). */
    public double spotRange = 3.0D;

    /** Key code that opens the settings GUI (customizable). */
    public int keyOpenGui = DEFAULT_KEY_OPEN_GUI;

    /** Observe invisible players: render them as translucent coloured silhouettes. */
    public boolean observeInvisible = true;

    /** Silhouette / afterimage opacity, 0.05 - 1.0 (default 80%). */
    public double opacity = 0.80D;

    /** Base colour of the silhouettes (RGB), default pure black. */
    public int baseColor = 0x000000;

    /** Enable afterimage trails (independent of {@link #observeInvisible}). */
    public boolean afterimagesEnabled = true;

    /** Maximum amount of afterimage frames kept per player. */
    public int afterimageCount = 12;

    /** How long afterimages linger (seconds) before they fade out. */
    public double afterimageLinger = 1.0D;

    /** Distance up to which silhouettes/afterimages are rendered. */
    public double maxRenderDistance = 96.0D;

    private final Configuration cfg;

    public Config(File file)
    {
        this.cfg = new Configuration(file);
        load();
        save();
    }

    /** (Re-)reads every value from the backing properties. */
    public void load()
    {
        if (cfg != null)
        {
            cfg.load();
        }

        enabled          = bool(CAT_GENERAL, "enabled", true, "Master switch of IllusionIndex.");
        spotChatEnabled  = bool(CAT_GENERAL, "chatAlerts", true,
                "Announce invisible players that are within attack range in chat.");
        spotRange        = dbl(CAT_GENERAL, "spotRange", 3.0D, 1.0D, 16.0D,
                "Attack range (in blocks) used for the chat spot alert.");
        keyOpenGui       = cfg.get(CAT_GENERAL, "openGuiKey", DEFAULT_KEY_OPEN_GUI,
                "Key that opens the IllusionIndex settings GUI (LWJGL key code, default 23 = I).", 0, 256).getInt();

        observeInvisible = bool(CAT_RENDER, "observeInvisible", true,
                "Render invisible players as semi-transparent silhouettes in the configured base colour.");
        opacity          = dbl(CAT_RENDER, "opacity", 0.80D, 0.05D, 1.0D,
                "Opacity of the silhouette / afterimage (0.05 - 1.0).");
        baseColor        = cfg.get(CAT_RENDER, "baseColor", 0x000000,
                "Base colour (RGB) used for the silhouettes, e.g. 0x000000 = pure black.", 0, 0xFFFFFF).getInt();
        maxRenderDistance = dbl(CAT_GENERAL, "maxRenderDistance", 96.0D, 8.0D, 256.0D,
                "Maximum distance at which silhouettes/afterimages are drawn.");

        afterimagesEnabled = bool(CAT_AFTERIMG, "afterimages", true,
                "Draw fading afterimage trails behind invisible players. Works even when observeInvisible is off.");
        afterimageCount    = cfg.get(CAT_AFTERIMG, "count", 12,
                "Maximum number of afterimage frames per player.", 2, 64).getInt();
        afterimageLinger   = dbl(CAT_AFTERIMG, "lingerSeconds", 1.0D, 0.1D, 5.0D,
                "How many seconds afterimages remain visible before fading out.");
    }

    private boolean bool(String cat, String key, boolean def, String comment)
    {
        cfg.setCategoryComment(cat, comment);
        return cfg.getBoolean(key, cat, def, comment);
    }

    private double dbl(String cat, String key, double def, double min, double max, String comment)
    {
        cfg.setCategoryComment(cat, comment);
        return cfg.getFloat(key, cat, (float) def, (float) min, (float) max, comment);
    }

    /** Writes all values back into the properties and to disk. */
    public void save()
    {
        if (cfg == null)
        {
            return;
        }
        cfg.get(CAT_GENERAL, "enabled", true, "Master switch of IllusionIndex.").set(enabled);
        cfg.get(CAT_GENERAL, "chatAlerts", true, "Announce invisible players that are within attack range in chat.").set(spotChatEnabled);
        cfg.get(CAT_GENERAL, "spotRange", 3.0D, "Attack range (in blocks) used for the chat spot alert.").set(spotRange);
        cfg.get(CAT_GENERAL, "openGuiKey", DEFAULT_KEY_OPEN_GUI, "Key that opens the IllusionIndex settings GUI.").set(keyOpenGui);
        cfg.get(CAT_GENERAL, "maxRenderDistance", 96.0D, "Maximum distance at which silhouettes/afterimages are drawn.").set(maxRenderDistance);

        cfg.get(CAT_RENDER, "observeInvisible", true, "Render invisible players as semi-transparent silhouettes in the configured base colour.").set(observeInvisible);
        cfg.get(CAT_RENDER, "opacity", 0.80D, "Opacity of the silhouette / afterimage (0.05 - 1.0).").set(opacity);
        cfg.get(CAT_RENDER, "baseColor", 0x000000, "Base colour (RGB) used for the silhouettes, e.g. 0x000000 = pure black.").set(baseColor);

        cfg.get(CAT_AFTERIMG, "afterimages", true, "Draw fading afterimage trails behind invisible players.").set(afterimagesEnabled);
        cfg.get(CAT_AFTERIMG, "count", 12, "Maximum number of afterimage frames per player.").set(afterimageCount);
        cfg.get(CAT_AFTERIMG, "lingerSeconds", 1.0D, "How many seconds afterimages remain visible before fading out.").set(afterimageLinger);

        cfg.save();
    }

    /** RGB components of {@link #baseColor}. */
    public float[] baseColorF()
    {
        return new float[] {
            (float) (baseColor >> 16 & 255) / 255.0F,
            (float) (baseColor >> 8  & 255) / 255.0F,
            (float) (baseColor       & 255) / 255.0F
        };
    }
}
