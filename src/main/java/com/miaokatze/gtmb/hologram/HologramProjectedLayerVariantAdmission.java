// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Lease;
import com.miaokatze.gtmb.hologram.HologramLayerStockBill.Need;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramWorkspace.ActionEndpoint;

/**
 * Scope-bound profile-2 metadata admission. No cell coordinates, Receiver, upload credit, world edits, withdrawal
 * or payment authority are issued here. Providers are checked at most one palette row per owner step.
 */
final class HologramProjectedLayerVariantAdmission {

    enum Source {
        OBSERVED_MAIN_ONLY, AUTHENTICATED_CREATIVE
    }

    enum Failure {
        INVALID, OWNER_CHANGED, RECOVERY_PENDING, UNSUPPORTED_STOCK_SOURCE, MISSING_STOCK
    }

    static final class Refused extends IOException {

        final Failure reason;

        private Refused(Failure reason) {
            super("Reviewed projected metadata not admitted: " + reason.name());
            this.reason = reason;
        }
    }

    /** Even completion of this scoped plan says only that bounded metadata and the selected source were proved. */
    static final class ReviewedMetadata {

        private final HologramProjectedLayerVariantAdmission admission;
        final HologramProjectedVariantPricing.Catalog catalog;
        final Source source;

        private ReviewedMetadata(HologramProjectedLayerVariantAdmission admission,
            HologramProjectedVariantPricing.Catalog catalog) {
            this.admission = admission;
            this.catalog = catalog;
            source = admission.source;
        }

        boolean currentFor(Manifest exactManifest, Lease exactLease) {
            return exactManifest == admission.manifest && exactLease == admission.lease
                && admission.current() && catalog.current();
        }
    }

    private final Thread owner = Thread.currentThread();
    private final ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final Lease lease;
    private final Manifest manifest;
    private final EntityPlayerMP player;
    private final HologramSession workspace;
    private final long workspaceEpoch, planRevision;
    private final InventoryPlayer inventory;
    private final ItemStack[] inventorySlots;
    private final int slot;
    private final HologramSessionPolicy.PreparationTool held;
    private final HologramMaterials.Settings settings;
    private final Source source;
    private final HologramProjectedVariantPricing.Builder pricing;
    private HologramProjectedVariantPricing.Catalog priced;
    private ReviewedMetadata result;
    private boolean stepping, refused;
    private int reviewed;

    HologramProjectedLayerVariantAdmission(ActionEndpoint endpoint, Lease lease, Manifest manifest) throws IOException {
        if (manifest == null || manifest.header.profile != HologramProjectedLayerWire.REVIEWED_PROFILE
            || endpoint == null || lease == null || lease.scope != endpoint.constructionScope
            || lease.request.kind != HologramConstructionTaskGate.Kind.PROJECTED_LAYER
            || !lease.request.id.equals(manifest.header.request)) throw failure(Failure.INVALID);
        gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null) throw failure(Failure.OWNER_CHANGED);
        this.endpoint = endpoint;
        this.lease = lease;
        this.manifest = manifest;
        player = endpoint.player;
        workspace = HologramWorkspace.find(endpoint.actor);
        workspaceEpoch = workspace == null ? -1 : workspace.editorWorkspaceEpoch;
        planRevision = workspace == null ? -1 : workspace.planRevision;
        inventory = player.inventory;
        inventorySlots = inventory.mainInventory;
        slot = inventory.currentItem;
        ItemStack submitted = player.getHeldItem();
        if (!ItemNekoHologramProjector.isProjector(submitted)) throw failure(Failure.OWNER_CHANGED);
        held = new HologramSessionPolicy.PreparationTool(submitted);
        settings = HologramMaterials.read(submitted);
        source = player.capabilities.isCreativeMode ? Source.AUTHENTICATED_CREATIVE : Source.OBSERVED_MAIN_ONLY;
        if (!current()) throw failure(Failure.OWNER_CHANGED);
        if (source == Source.OBSERVED_MAIN_ONLY && (!settings.main || settings.containers || settings.me))
            throw failure(Failure.UNSUPPORTED_STOCK_SOURCE);
        if (HologramProjectedLayerService.hasKnownRecovery(player)) throw failure(Failure.RECOVERY_PENDING);
        if (!current() || !positionCurrent()) throw failure(Failure.OWNER_CHANGED);
        pricing = new HologramProjectedVariantPricing.Builder(manifest.header.palette.size());
    }

    Source source() {
        return source;
    }

    int reviewedRows() {
        return reviewed;
    }

    /** Returns null while pricing. There is no path from this non-mutating result to slice upload or placement. */
    ReviewedMetadata step(long deadline) throws IOException {
        if (stepping || !current()) {
            if (Thread.currentThread() == owner) refused = true;
            throw failure(Failure.OWNER_CHANGED);
        }
        if (result != null) {
            if (result.currentFor(manifest, lease)) return result;
            throw failure(Failure.OWNER_CHANGED);
        }
        if (System.nanoTime() >= deadline) return null;
        stepping = true;
        try {
            if (!positionCurrent() || !current()) throw failure(Failure.OWNER_CHANGED);
            if (HologramProjectedLayerService.hasKnownRecovery(player)) throw failure(Failure.RECOVERY_PENDING);
            if (reviewed < manifest.header.palette.size()) {
                pricing.add(manifest.header.palette.get(reviewed).reviewedRow());
                if (!current()) throw failure(Failure.OWNER_CHANGED);
                reviewed++;
                return null;
            }
            priced = pricing.finish();
            if (!priced.current() || !current()) throw failure(Failure.OWNER_CHANGED);
            if (source == Source.OBSERVED_MAIN_ONLY) {
                // One synchronous exact census immediately before proof publication, not independent observations that
                // could have been taken over different inventories. A result is not escrow or future debit authority.
                var stock = new HologramMaterials.Context(player, settings, slot);
                for (Need need : priced.bill.required()) {
                    if (stock.mainCount(need.unit(), need.count) != need.count) throw failure(Failure.MISSING_STOCK);
                }
            }
            if (!priced.current() || !current() || !positionCurrent() || !current())
                throw failure(Failure.OWNER_CHANGED);
            result = new ReviewedMetadata(this, priced);
            return result;
        } catch (IOException failure) {
            refused = true;
            throw failure;
        } catch (RuntimeException | LinkageError unavailable) {
            refused = true;
            throw new IOException("Reviewed projected metadata provider unavailable", unavailable);
        } finally {
            stepping = false;
        }
    }

    private boolean current() {
        if (refused || Thread.currentThread() != owner || HologramWorkspace.constructionGate(endpoint) != gate
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE
            || player.isDead || !player.capabilities.allowEdit || player.inventory != inventory
            || inventory.mainInventory != inventorySlots || inventory.currentItem != slot
            || HologramWorkspace.find(endpoint.actor) != workspace || HologramProjectedLayerService.competingWork(player)
            || workspace != null && (workspace.editorWorkspaceEpoch != workspaceEpoch
                || workspace.planRevision != planRevision)
            || (source == Source.AUTHENTICATED_CREATIVE) != player.capabilities.isCreativeMode)
            return false;
        ItemStack current = player.getHeldItem();
        if (!held.matches(current) || !ItemNekoHologramProjector.isProjector(current)) return false;
        var observed = HologramMaterials.read(current);
        return observed.main == settings.main && observed.containers == settings.containers
            && observed.me == settings.me && observed.priority == settings.priority
            && observed.recoveryDestination == settings.recoveryDestination;
    }

    private boolean positionCurrent() {
        Header header = manifest.header;
        var world = endpoint.world;
        int height = world.getHeight();
        return header.slices <= HologramProjectedLayerService.MAX_SLICES && header.maxY < height
            && HologramWorldFlow.worldPosition(header.x, header.y, header.z, height)
            && near(header.x, header.minX, header.maxX) && near(header.y, header.minY, header.maxY)
            && near(header.z, header.minZ, header.maxZ) && world.blockExists(header.x, header.y, header.z)
            && HologramSessionPolicy.previewReach(player, header.x, header.y, header.z);
    }

    private static boolean near(int anchor, int min, int max) {
        return (long) min >= (long) anchor - 256 && (long) max <= (long) anchor + 256;
    }

    private static Refused failure(Failure reason) {
        return new Refused(reason);
    }
}
