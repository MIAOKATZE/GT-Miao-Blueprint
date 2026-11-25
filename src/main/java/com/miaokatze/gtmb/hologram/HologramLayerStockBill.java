// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * Exact raw-only arithmetic for a bounded palette, with one fractional ledger for the whole job. This is neither
 * inventory observation nor a transaction. A driver must coordinate each quote with its actual world/payment
 * commit; never credit already-satisfied cells, a transfer ACK, a failed debit, or an unresolved journal.
 */
final class HologramLayerStockBill {

    static final int MAX_ROWS = 32, MAX_KINDS = 16, MAX_TERMS_PER_ROW = 2, MAX_RATE = 4096;

    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    private final Row[] rows;
    private final Resource[] resources;
    private final int[][] columns;
    private final int[] totals;

    static final class Resource {

        final Item item;
        final int damage;

        private Resource(ItemStack unit) {
            item = unit.getItem();
            damage = unit.getItemDamage();
        }

        ItemStack stack(int quantity) {
            return new ItemStack(item, quantity, damage);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Resource resource && resource.item == item && resource.damage == damage;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(item) + damage;
        }
    }

    static final class Term {

        private final Resource resource;
        private final long numerator, denominator;

        Term(ItemStack exactUntaggedUnit, long numerator, long denominator) throws IOException {
            if (exactUntaggedUnit == null || exactUntaggedUnit.getItem() == null || exactUntaggedUnit.stackSize != 1
                || exactUntaggedUnit.getItemDamage() < 0 || exactUntaggedUnit.getItemDamage() > Short.MAX_VALUE
                || exactUntaggedUnit.hasTagCompound() || numerator <= 0 || numerator > MAX_RATE
                || denominator <= 0 || denominator > MAX_RATE) throw invalid();
            resource = new Resource(exactUntaggedUnit);
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    static final class Row {

        private final Term[] parts;
        final int count;

        Row(int count, Term... parts) throws IOException {
            if (count < 1 || count > HologramBlueprintLimits.LOCAL_CELLS || parts == null || parts.length == 0
                || parts.length > MAX_TERMS_PER_ROW) throw invalid();
            for (Term part : parts) if (part == null) throw invalid();
            this.count = count;
            this.parts = parts.clone();
        }
    }

    static final class Need {

        final Resource resource;
        final int count;

        private Need(Resource resource, int count) {
            this.resource = resource;
            this.count = count;
        }

        /** Unit identity for stock inspection or an already authoritative payment transaction. */
        ItemStack unit() {
            return resource.stack(1);
        }
    }

    private static final class Fraction {

        final BigInteger numerator, denominator;

        Fraction(BigInteger numerator, BigInteger denominator) {
            this.numerator = numerator;
            this.denominator = denominator;
        }

        Fraction plus(Term term, int occurrences) {
            BigInteger divisor = BigInteger.valueOf(term.denominator);
            BigInteger top = numerator.multiply(divisor)
                .add(denominator.multiply(BigInteger.valueOf(term.numerator * occurrences)));
            BigInteger bottom = denominator.multiply(divisor);
            BigInteger gcd = top.gcd(bottom);
            return new Fraction(top.divide(gcd), bottom.divide(gcd));
        }

        int ceil() throws IOException {
            BigInteger count = numerator.add(denominator).subtract(BigInteger.ONE).divide(denominator);
            if (count.signum() < 0 || count.compareTo(INT_MAX) > 0) throw invalid();
            return count.intValue();
        }
    }

    HologramLayerStockBill(List<Row> palette) throws IOException {
        if (palette == null || palette.isEmpty() || palette.size() > MAX_ROWS) throw invalid();
        rows = palette.toArray(new Row[0]);
        columns = new int[rows.length][];
        Map<Resource, Integer> kinds = new LinkedHashMap<>();
        Fraction[] sums = new Fraction[MAX_KINDS];
        long cells = 0;
        for (int row = 0; row < rows.length; row++) {
            Row entry = rows[row];
            if (entry == null || (cells += entry.count) > HologramBlueprintLimits.LOCAL_CELLS) throw invalid();
            columns[row] = new int[entry.parts.length];
            for (int part = 0; part < entry.parts.length; part++) {
                Term term = entry.parts[part];
                Integer column = kinds.get(term.resource);
                if (column == null) {
                    if (kinds.size() >= MAX_KINDS) throw invalid();
                    column = kinds.size();
                    kinds.put(term.resource, column);
                    sums[column] = ZERO;
                }
                columns[row][part] = column;
                sums[column] = sums[column].plus(term, entry.count);
            }
        }
        resources = kinds.keySet().toArray(new Resource[0]);
        totals = new int[resources.length];
        for (int i = 0; i < totals.length; i++) totals[i] = sums[i].ceil();
    }

    /** Whole dry upper bound, one rounded total per real physical item variant, independent of slice ordering. */
    List<Need> required() {
        List<Need> result = new ArrayList<>(totals.length);
        for (int i = 0; i < totals.length; i++) result.add(new Need(resources[i], totals[i]));
        return Collections.unmodifiableList(result);
    }

    Cursor cursor() {
        return new Cursor();
    }

    final class Cursor {

        private final Thread owner = Thread.currentThread();
        private final Fraction[] committed = new Fraction[resources.length];
        private final int[] remaining = new int[rows.length];
        private long revision;

        private Cursor() {
            java.util.Arrays.fill(committed, ZERO);
            for (int i = 0; i < rows.length; i++) remaining[i] = rows[i].count;
        }

        Quote quote(int variant) throws IOException {
            requireAvailable(variant);
            Row entry = rows[variant];
            int[] indices = columns[variant].clone();
            Fraction[] proposal = new Fraction[entry.parts.length];
            List<Need> due = new ArrayList<>(entry.parts.length);
            for (int i = 0; i < entry.parts.length; i++) {
                int index = indices[i];
                Fraction previous = committed[index], after = previous.plus(entry.parts[i], 1);
                // A native primary and cladding can use the same stock. Combine before computing this debit.
                if (i > 0 && indices[0] == index) {
                    after = proposal[0].plus(entry.parts[i], 1);
                    proposal[0] = after;
                    due.clear();
                    indices[i] = -1;
                } else proposal[i] = after;
                int debit = after.ceil() - previous.ceil();
                if (debit > 0) due.add(new Need(resources[index], debit));
            }
            return new Quote(this, revision, variant, indices, proposal, due);
        }

        void confirmedPlacement(Quote exact) throws IOException {
            if (Thread.currentThread() != owner || exact == null || exact.owner != this || exact.revision != revision)
                throw invalid();
            requireAvailable(exact.variant);
            for (int i = 0; i < exact.indices.length; i++) {
                int column = exact.indices[i];
                if (column >= 0) committed[column] = exact.proposal[i];
            }
            remaining[exact.variant]--;
            revision++;
        }

        void confirmedAlreadySatisfied(int variant) throws IOException {
            requireAvailable(variant);
            remaining[variant]--;
            revision++;
        }

        private void requireAvailable(int variant) throws IOException {
            if (Thread.currentThread() != owner || variant < 0 || variant >= rows.length || remaining[variant] <= 0)
                throw invalid();
        }
    }

    static final class Quote {

        final List<Need> debit;
        private final Cursor owner;
        private final long revision;
        private final int variant;
        private final int[] indices;
        private final Fraction[] proposal;

        private Quote(Cursor owner, long revision, int variant, int[] indices, Fraction[] proposal, List<Need> debit) {
            this.owner = owner;
            this.revision = revision;
            this.variant = variant;
            this.indices = indices;
            this.proposal = proposal;
            this.debit = Collections.unmodifiableList(debit);
        }
    }

    private static IOException invalid() {
        return new IOException("Invalid bounded raw-material bill");
    }
}
