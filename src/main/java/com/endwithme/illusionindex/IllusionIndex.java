package com.endwithme.illusionindex;

import com.endwithme.illusionindex.gui.GuiConfig;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * IllusionIndex - reveals invisible players on Minecraft 1.8.9 (client side).
 *
 * <p>Core responsibilities:
 * <ul>
 *   <li>Render invisible players as flat, semi-transparent silhouettes in a configurable base
 *       colour (their original skin texture is never shown).</li>
 *   <li>Keep fading afterimage trails of invisible players.</li>
 *   <li>Announce invisible players that come within attack range via chat.</li>
 *   <li>Provide a shader driven settings GUI bound to a key (default: I).</li>
 * </ul>
 *
 * @author EndWithMe
 */
@Mod(modid = IllusionIndex.MODID, name = IllusionIndex.NAME, version = IllusionIndex.VERSION)
public class IllusionIndex
{
    public static final String MODID = "illusionindex";
    public static final String NAME = "IllusionIndex";
    public static final String VERSION = "1.0.0";

    @Mod.Instance(MODID)
    public static IllusionIndex instance;

    /** Persisted settings (config/IllusionIndex.cfg). */
    public Config config;

    /** Key bindings (open GUI / quick toggles). */
    public KeyBindings keyBindings;

    /** World render + chat logic. */
    public RenderHandler renderHandler;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event)
    {
        instance = this;
        this.config = new Config(event.getSuggestedConfigurationFile());

        if (event.getSide().isClient())
        {
            this.keyBindings = new KeyBindings(this.config);
            this.renderHandler = new RenderHandler(this.config);
            MinecraftForge.EVENT_BUS.register(this.renderHandler);
            MinecraftForge.EVENT_BUS.register(this.keyBindings);
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event)
    {
    }

    /** Opens the shader driven config GUI. */
    public static void openConfigGui()
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null && mc.currentScreen == null)
        {
            mc.displayGuiScreen(new GuiConfig());
        }
    }
}
