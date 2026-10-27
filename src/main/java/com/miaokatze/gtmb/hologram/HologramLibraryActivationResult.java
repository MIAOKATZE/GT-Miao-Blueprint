package com.miaokatze.gtmb.hologram;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.nbt.NBTTagCompound;

/** Owner-thread terminal receipt. Admission is not completion and late callbacks cannot acknowledge a replacement. */
final class HologramLibraryActivationResult implements HologramLibraryFacade.ActivationCompletion {

    private final NBTTagCompound identity;
    private final String reply;
    private final BooleanSupplier sourceCurrent, transportCurrent;
    private final Consumer<NBTTagCompound> sink;
    private boolean terminal;
    private BooleanSupplier confirmationCurrent = () -> true;

    HologramLibraryActivationResult(NBTTagCompound request, String reply, BooleanSupplier sourceCurrent,
        BooleanSupplier transportCurrent, Consumer<NBTTagCompound> sink) {
        identity = (NBTTagCompound) request.copy();
        this.reply = reply;
        this.sourceCurrent = sourceCurrent;
        this.transportCurrent = transportCurrent;
        this.sink = sink;
    }

    @Override
    public boolean current() {
        return !terminal && transportCurrent.getAsBoolean() && sourceCurrent.getAsBoolean()
            && confirmationCurrent.getAsBoolean();
    }

    @Override
    public void waitingForConfirmation(BooleanSupplier current) {
        confirmationCurrent = current == null ? () -> true : current;
    }

    boolean terminal() {
        return terminal;
    }

    boolean matches(NBTTagCompound request) {
        return identity.getString("session").equals(request.getString("session"))
            && identity.getLong("clientTaskToken") == request.getLong("clientTaskToken")
            && identity.getString("transfer").equals(request.getString("transfer"));
    }

    boolean supersededBy(NBTTagCompound request) {
        long previous = identity.getLong("clientTaskToken"), incoming = request.getLong("clientTaskToken");
        return incoming == 0 || previous == 0 || incoming > previous;
    }

    boolean transportCurrent() {
        return transportCurrent.getAsBoolean();
    }

    void poll() {
        if (!terminal && !current()) failed("Blueprint activation cancelled or source changed; original editor retained");
    }

    @Override
    public void completed(String id) {
        if (terminal) return;
        // The successful presentation has replaced the source session. Recheck transport, not the retired source.
        if (!transportCurrent.getAsBoolean()) {
            failed("Blueprint activation result was cancelled");
            return;
        }
        terminal = true;
        confirmationCurrent = () -> true;
        NBTTagCompound result = envelope(reply);
        result.setString("id", id == null ? "" : id);
        sink.accept(result);
    }

    @Override
    public void failed(String reason) {
        if (terminal) return;
        terminal = true;
        confirmationCurrent = () -> true;
        NBTTagCompound result = envelope("error");
        result.setString("message", reason == null ? "Blueprint activation failed" : reason);
        sink.accept(result);
    }

    private NBTTagCompound envelope(String operation) {
        NBTTagCompound result = HologramLibraryProtocol.reply(operation);
        result.setString("session", identity.getString("session"));
        result.setString("transfer", identity.getString("transfer"));
        result.setLong("clientTaskToken", identity.getLong("clientTaskToken"));
        return result;
    }
}
