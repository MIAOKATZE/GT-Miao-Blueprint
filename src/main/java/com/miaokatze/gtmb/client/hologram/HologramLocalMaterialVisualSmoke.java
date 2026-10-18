package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramMaterialQuote;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Default-off one-frame native MUI fixture. No world/server, input injection, draw simulation, or construction. */
public final class HologramLocalMaterialVisualSmoke {

    private final long started = System.nanoTime();
    private final JsonObject receipt = new JsonObject();
    private HologramLocalMaterialScreen screen;
    private File output;
    private long opened;
    private boolean finished;

    static boolean install() {
        if (!Boolean.getBoolean("gtmb.hologram.localMaterialVisualSmoke")) return false;
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramLocalMaterialVisualSmoke());
        return true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (output == null) {
                output = new File(
                    System.getProperty(
                        "gtmb.hologram.localMaterialVisualOutput",
                        new File(mc.mcDataDir, "../../local-material-visual").getCanonicalPath()));
                Files.createDirectories(output.toPath());
                receipt.addProperty(
                    "scope",
                    "menu-only synthetic static demand; no world/server or survival payment proof");
                receipt.addProperty(
                    "maxHeapBytes",
                    Runtime.getRuntime()
                        .maxMemory());
                require(
                    Runtime.getRuntime()
                        .maxMemory() <= 5120L * 1024 * 1024,
                    "visual client heap exceeds 5120 MiB");
                write(false, false, "waiting for main menu");
            }
            require(mc.theWorld == null && mc.getNetHandler() == null, "fixture cannot join a world or server");
            if (screen == null) {
                boolean menu = mc.currentScreen instanceof GuiMainMenu
                    || mc.currentScreen != null && "lumien.custommainmenu.gui.GuiCustom".equals(
                        mc.currentScreen.getClass()
                            .getName());
                require(System.nanoTime() - started < 1800_000_000_000L, "main menu startup timeout");
                if (!menu) return;
                mc.gameSettings.guiScale = Integer.getInteger("gtmb.hologram.materialVisualScale", 2);
                screen = HologramLocalMaterialScreen.visualFixture(fixture());
                mc.displayGuiScreen(screen);
                opened = System.nanoTime();
                write(false, false, "waiting for naturally rendered local material screen");
            } else require(System.nanoTime() - opened < 30_000_000_000L, "local material natural frame timeout");
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void rendered(TickEvent.RenderTickEvent event) {
        if (finished || screen == null || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            NBTTagCompound state = screen.visualState();
            if (state.getLong("drawnFrames") < 3 || System.nanoTime() - opened < 900_000_000L) return;
            require(
                mc.currentScreen == screen && mc.theWorld == null && mc.getNetHandler() == null,
                "naturally rendered fixture must own the disconnected client screen");
            require(
                !state.getBoolean("constructionEnabled") && !state.getBoolean("stockKnown"),
                "fixture must keep construction disabled and unqueried inventory unknown");
            require(state.getInteger("rows") > 12, "fixture must exercise a clipped overflowing material list");
            String name = "local-material-unknown-stock.png";
            ScreenShotHelper.saveScreenshot(output, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            File png = new File(new File(output, "screenshots"), name);
            require(png.isFile() && png.length() > 0, "natural framebuffer screenshot must exist");
            receipt.addProperty("png", png.getCanonicalPath());
            receipt.addProperty("pngBytes", png.length());
            receipt.addProperty("naturalRenderTickEnd", true);
            receipt.addProperty("drawnFrames", state.getLong("drawnFrames"));
            receipt.addProperty("rows", state.getInteger("rows"));
            receipt.addProperty("constructionEnabled", state.getBoolean("constructionEnabled"));
            receipt.addProperty("stockKnown", state.getBoolean("stockKnown"));
            receipt.addProperty("visualStateNbt", state.toString());
            finish(mc, true, "local material screen naturally rendered; construction disabled and stock unknown");
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private NBTTagCompound fixture() {
        NBTTagCompound summary = new NBTTagCompound();
        summary.setInteger("version", 1);
        summary.setBoolean("complete", true);
        summary.setBoolean("cacheReviewRequired", true);
        NBTTagList rows = new NBTTagList();
        add(rows, new ItemStack(Items.sign), "123456789012345678901234567890");
        ItemStack carpenter = null;
        for (Object value : Block.blockRegistry) {
            if (!(value instanceof Block)) continue;
            Block block = (Block) value;
            if (block.getClass()
                .getName()
                .equals("com.carpentersblocks.block.BlockCarpentersBlock")) {
                Item item = Item.getItemFromBlock(block);
                if (item != null) carpenter = new ItemStack(item);
                break;
            }
        }
        require(carpenter != null, "packaged fixture must contain the registered Carpenter block item");
        add(rows, carpenter, "4294967301");
        for (int i = 0; i < 18; i++) {
            ItemStack tagged = new ItemStack(Items.diamond);
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("localVisualVariant", "exact-NBT-variant-" + i);
            tag.setString(
                "description",
                "long retained item identity for local material inspection and independent quantities");
            tagged.setTagCompound(tag);
            add(rows, tagged, Long.toString(4_294_967_300L + i));
        }
        FluidStack water = new FluidStack(FluidRegistry.WATER, 1);
        NBTTagCompound fluid = new NBTTagCompound();
        fluid.setString("kind", "fluid");
        fluid.setString("unit", "mB");
        fluid.setString("requiredExact", "98765432109876543210");
        fluid.setBoolean("availableKnown", false);
        fluid.setTag("fluid", water.writeToNBT(new NBTTagCompound()));
        rows.appendTag(fluid);
        summary.setTag("rows", rows);
        receipt.addProperty("signItem", true);
        receipt.addProperty("carpenterItem", true);
        receipt.addProperty("exactTaggedVariants", 18);
        return summary;
    }

    private static void add(NBTTagList rows, ItemStack item, String quantity) {
        NBTTagCompound row = HologramMaterialQuote.itemRow(item, 1, 0);
        row.setString("requiredExact", quantity);
        row.setBoolean("availableKnown", false);
        rows.appendTag(row);
    }

    private void finish(Minecraft mc, boolean passed, String detail) {
        finished = true;
        try {
            if (output != null) write(true, passed, detail);
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        System.out.println("[GTMB-LOCAL-MATERIAL-VISUAL] FINAL " + (passed ? "PASS " : "FAIL ") + detail);
        mc.shutdown();
    }

    private void write(boolean complete, boolean passed, String detail) throws Exception {
        receipt.addProperty("completed", complete);
        receipt.addProperty("passed", passed);
        receipt.addProperty("detail", detail);
        Files.write(
            new File(output, "local-material-visual-receipt.json").toPath(),
            (new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }
}
