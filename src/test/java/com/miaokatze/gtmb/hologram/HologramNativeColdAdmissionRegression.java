// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** Real cold server PROBE/BEGIN reservation and cancellation, without granting a world-mutation permit. */
final class HologramNativeColdAdmissionRegression {

    private static final int CONTROLLER_ID = 32667;

    private HologramNativeColdAdmissionRegression() {}

    static void run() {
        try {
            HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
                try {
                    for (String scenario : new String[] { "unchanged", "toolReplaced", "anchorReplaced",
                        "accessRevoked" }) coldRoundTrip(scenario);
                } catch (Exception failure) {
                    throw new AssertionError("cold native reservation fixture", failure);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError("offline cold native reservation environment", failure);
        }
    }

    private static void coldRoundTrip(String scenario) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        Block oldMachines = GregTechAPI.sBlockMachines;
        IMetaTileEntity oldPrototype = GregTechAPI.METATILEENTITIES[CONTROLLER_ID];
        var world = HologramPlacementFixtureWorld.create(unsafe);
        var player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "NativeColdAdmission");
        player.posY = 64;
        player.capabilities.allowEdit = true;
        player.capabilities.isCreativeMode = false;
        UUID tool = UUID.randomUUID();
        var tag = new NBTTagCompound();
        tag.setString(HologramSessionKeys.TOOL_ID, tool.toString());
        player.getHeldItem()
            .setTagCompound(tag);
        var held = player.getHeldItem();
        var replies = new ArrayList<HologramStockNativeWire.Reply>();
        var serverRef = new HologramStockNativeServer[1];
        var server = new HologramStockNativeServer((endpoint, reply) -> {
            replies.add(reply);
            if (reply.phase() == HologramStockNativeWire.Phase.ACCEPTED) {
                var seat = HologramWorkspace.find(endpoint.actor);
                SimpleAssert.that(
                    seat != null && seat.stockNativePrivate && seat.boundTool == held,
                    "accepted admission owns the exact marked private seat and authenticated raw tool");
                SimpleAssert.that(
                    serverRef[0].enqueue(
                        endpoint,
                        HologramStockNativeWire.readRequest(
                            HologramStockNativeWire.control(reply.key(), HologramStockNativeWire.Command.STOP))),
                    "an exact STOP is queued before the admitted job can obtain a mutation permit");
            }
        });
        serverRef[0] = server;
        try {
            // This fixture needs no ItemMachines placement, hatch catalog, casing registration or EBF static shape.
            GregTechAPI.sBlockMachines = Blocks.iron_block;
            GregTechAPI.METATILEENTITIES[CONTROLLER_ID] = new Controller();
            var anchor = new BaseMetaTileEntity();
            anchor.setWorldObj(world);
            anchor.yCoord = 64;
            anchor.setInitialValuesAsNBT(null, (short) CONTROLLER_ID);
            var machine = (Controller) anchor.getMetaTileEntity();
            machine.setExtendedFacing(ExtendedFacing.DEFAULT);
            world.occupants = new HashMap<>();
            world.occupants.put("0,64,0", anchor);
            world.seed(0, 64, 0, GregTechAPI.sBlockMachines, 0);
            var target = HologramNativeTargetPlan.compile(
                CONTROLLER_ID,
                ExtendedFacing.DEFAULT,
                HologramNativeTargetPlan.canonicalTrigger(1, new NBTTagCompound()),
                new NBTTagCompound());
            SimpleAssert.that(
                target.complete() && target.cells()
                    .size() == 1
                    && target.cells()
                        .get(0)
                        .requiredAir(),
                "the fixture target is independently produced by the real native compiler");
            var projection = new HologramStockNativeWire.Projection(
                CONTROLLER_ID,
                HologramStockNativeWire.Operation.BUILD,
                Collections.emptyMap(),
                Collections.emptyList(),
                0,
                1).withNativeTargetDigest(target.targetDigest());
            HologramWorkspace.publishActionEndpoint(player, false);
            var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            SimpleAssert.that(endpoint != null, "the real player transport has a lifecycle-published endpoint");
            var key = new HologramStockNativeWire.Key(UUID.randomUUID(), 1, 0);
            var confirmation = HologramStockNativeWire.Confirmation.projection(
                key,
                player.dimension,
                tool,
                0,
                64,
                0,
                ForgeDirection.NORTH.ordinal(),
                0,
                ExtendedFacing.DEFAULT.ordinal(),
                1,
                false,
                false,
                1,
                projection);
            var probe = HologramStockNativeWire.readRequest(HologramStockNativeWire.probe(confirmation));
            SimpleAssert.that(server.enqueue(endpoint, probe), "encoded cold PROBE enters the real server queue");
            server.tick(Long.MAX_VALUE);
            var capable = replies.get(replies.size() - 1);
            SimpleAssert.that(
                capable.phase() == HologramStockNativeWire.Phase.CAPABLE && capable.observation()
                    .stage() == HologramStockNativeWire.Stage.CONTROLLER_ONLY,
                "native observation confirms the empty site before a cold BUILD");
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == null,
                "observation cannot seize an editor or private execution workspace");
            if (scenario.equals("toolReplaced")) player.inventory.mainInventory[0] = held.copy();
            if (scenario.equals("anchorReplaced")) {
                var replacement = new BaseMetaTileEntity();
                replacement.setWorldObj(world);
                replacement.yCoord = 64;
                replacement.setInitialValuesAsNBT(null, (short) CONTROLLER_ID);
                ((Controller) replacement.getMetaTileEntity()).setExtendedFacing(ExtendedFacing.DEFAULT);
                world.occupants.put("0,64,0", replacement);
            }
            if (scenario.equals("accessRevoked")) machine.allow = false;
            var begun = confirmation.forGeneration(
                capable.key()
                    .generation());
            var begin = HologramStockNativeWire.readRequest(HologramStockNativeWire.confirmAfterProbe(begun));
            SimpleAssert.that(server.enqueue(endpoint, begin), "encoded cold BEGIN enters the actual admission queue");
            // The reply sink queues an exact STOP synchronously at ACCEPTED. The real server consumes
            // its priority control queue before advancing the newly admitted job or granting a permit.
            server.tick(Long.MAX_VALUE);
            boolean accepted = replies.stream()
                .anyMatch(reply -> reply.phase() == HologramStockNativeWire.Phase.ACCEPTED);
            if (scenario.equals("unchanged")) {
                SimpleAssert.that(
                    accepted,
                    "unchanged cold proof must acquire its marked private workspace before native preparation");
                SimpleAssert.that(
                    replies.get(replies.size() - 1)
                        .phase() == HologramStockNativeWire.Phase.STOPPED,
                    "the exact admitted job can stop through its normal authenticated control path");
            } else {
                SimpleAssert.that(
                    !accepted && replies.get(replies.size() - 1)
                        .phase() == HologramStockNativeWire.Phase.REFUSED,
                    "cold BEGIN refuses changed raw tool, anchor or native access: " + scenario);
            }
            SimpleAssert.that(
                HologramWorkspace.find(player.getUniqueID()) == null,
                "stopped or refused cold admission releases only its private execution seat");
            SimpleAssert.that(
                world.writes()
                    .isEmpty(),
                "cold admission fixture never changes the player's world");
            SimpleAssert.that(
                player.getHeldItem().stackSize == 1 && tag.equals(held.getTagCompound()),
                "admission does not rewrite the authenticated tool tag or charge materials");
        } finally {
            server.reset();
            HologramWorkspace.revokeActionEndpoint(player);
            player.playerNetServerHandler.netManager.channel()
                .close();
            HologramNativeProjectionJob.clearBaselines();
            GregTechAPI.METATILEENTITIES[CONTROLLER_ID] = oldPrototype;
            GregTechAPI.sBlockMachines = oldMachines;
        }
    }

    private static final class Controller extends MTEEnhancedMultiBlockBase<Controller>
        implements ISurvivalConstructable {

        private static final IStructureElement<Controller> AIR = new IStructureElement<Controller>() {

            @Override
            public boolean check(Controller context, World world, int x, int y, int z) {
                return world.isAirBlock(x, y, z) && world.getTileEntity(x, y, z) == null;
            }

            @Override
            public boolean spawnHint(Controller context, World world, int x, int y, int z, ItemStack trigger) {
                return true;
            }

            @Override
            public boolean placeBlock(Controller context, World world, int x, int y, int z, ItemStack trigger) {
                return false;
            }
        };
        private static final IStructureDefinition<Controller> DEFINITION = StructureDefinition.<Controller>builder()
            .addShape("main", new String[][] { { "A" } })
            .addElement('A', AIR)
            .build();
        boolean allow = true;

        Controller() {
            super("gtmb_cold_admission_fixture");
        }

        @Override
        public boolean isAccessAllowed(EntityPlayer player) {
            return allow;
        }

        @Override
        public IStructureDefinition<Controller> getStructureDefinition() {
            return DEFINITION;
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            var tile = getBaseMetaTileEntity();
            DEFINITION.buildOrHints(
                this,
                trigger,
                "main",
                tile.getWorld(),
                getExtendedFacing(),
                tile.getXCoord(),
                tile.getYCoord(),
                tile.getZCoord(),
                -1,
                0,
                0,
                hints);
            // The detached bootstrap does not apply the native buildOrHints observation mixin or
            // Forge event-constructor transformer. Use the same explicit visited-event seam as the
            // existing native preview fixtures, with the one coordinate declared by this test shape.
            HologramNativeTargetPlan.piece(DEFINITION, this, trigger, tile.getWorld());
            try {
                Constructor<StructureElementVisitedEvent> constructor = StructureElementVisitedEvent.class
                    .getDeclaredConstructor(
                        World.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        IStructureElement.class);
                constructor.setAccessible(true);
                new HologramNativeTargetPlan.Listener().visit(
                    constructor.newInstance(
                        tile.getWorld(),
                        tile.getXCoord() + 1,
                        tile.getYCoord(),
                        tile.getZCoord(),
                        1,
                        0,
                        0,
                        AIR));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("offline cold native visited-event seam", failure);
            }
        }

        @Override
        public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment) {
            return -1;
        }

        @Override
        public boolean checkStructure(boolean reset, IGregTechTileEntity tile) {
            mMachine = false;
            return false;
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return new MultiblockTooltipBuilder();
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new Controller();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }
}
