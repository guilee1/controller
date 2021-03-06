/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.primitives.UnsignedLong;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.opendaylight.controller.cluster.access.commands.AbstractReadTransactionRequest;
import org.opendaylight.controller.cluster.access.commands.ClosedTransactionException;
import org.opendaylight.controller.cluster.access.commands.CommitLocalTransactionRequest;
import org.opendaylight.controller.cluster.access.commands.DeadTransactionException;
import org.opendaylight.controller.cluster.access.commands.LocalHistorySuccess;
import org.opendaylight.controller.cluster.access.commands.OutOfOrderRequestException;
import org.opendaylight.controller.cluster.access.commands.TransactionPurgeRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionPurgeResponse;
import org.opendaylight.controller.cluster.access.commands.TransactionRequest;
import org.opendaylight.controller.cluster.access.commands.TransactionSuccess;
import org.opendaylight.controller.cluster.access.concepts.LocalHistoryIdentifier;
import org.opendaylight.controller.cluster.access.concepts.RequestEnvelope;
import org.opendaylight.controller.cluster.access.concepts.RequestException;
import org.opendaylight.controller.cluster.access.concepts.TransactionIdentifier;
import org.opendaylight.yangtools.concepts.Identifiable;
import org.opendaylight.yangtools.yang.data.api.schema.tree.DataTreeModification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract class for providing logical tracking of frontend local histories. This class is specialized for
 * standalone transactions and chained transactions.
 *
 * @author Robert Varga
 */
abstract class AbstractFrontendHistory implements Identifiable<LocalHistoryIdentifier> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractFrontendHistory.class);
    private static final OutOfOrderRequestException UNSEQUENCED_START = new OutOfOrderRequestException(0);

    private final Map<TransactionIdentifier, FrontendTransaction> transactions = new HashMap<>();
    private final RangeSet<UnsignedLong> purgedTransactions;
    private final String persistenceId;
    private final ShardDataTree tree;

    /**
     * Transactions closed by the previous leader. Boolean indicates whether the transaction was committed (true) or
     * aborted (false). We only ever shrink these.
     */
    private Map<UnsignedLong, Boolean> closedTransactions;

    AbstractFrontendHistory(final String persistenceId, final ShardDataTree tree,
        final Map<UnsignedLong, Boolean> closedTransactions, final RangeSet<UnsignedLong> purgedTransactions) {
        this.persistenceId = Preconditions.checkNotNull(persistenceId);
        this.tree = Preconditions.checkNotNull(tree);
        this.closedTransactions = Preconditions.checkNotNull(closedTransactions);
        this.purgedTransactions = Preconditions.checkNotNull(purgedTransactions);
    }

    final String persistenceId() {
        return persistenceId;
    }

    final long readTime() {
        return tree.ticker().read();
    }

    final @Nullable TransactionSuccess<?> handleTransactionRequest(final TransactionRequest<?> request,
            final RequestEnvelope envelope, final long now) throws RequestException {
        final TransactionIdentifier id = request.getTarget();
        final UnsignedLong ul = UnsignedLong.fromLongBits(id.getTransactionId());

        if (request instanceof TransactionPurgeRequest) {
            if (purgedTransactions.contains(ul)) {
                // Retransmitted purge request: nothing to do
                LOG.debug("{}: transaction {} already purged", persistenceId, id);
                return new TransactionPurgeResponse(id, request.getSequence());
            }

            // We perform two lookups instead of a straight remove, because once the map becomes empty we switch it
            // to an ImmutableMap, which does not allow remove().
            if (closedTransactions.containsKey(ul)) {
                tree.purgeTransaction(id, () -> {
                    closedTransactions.remove(ul);
                    if (closedTransactions.isEmpty()) {
                        closedTransactions = ImmutableMap.of();
                    }

                    purgedTransactions.add(Range.singleton(ul));
                    LOG.debug("{}: finished purging inherited transaction {}", persistenceId(), id);
                    envelope.sendSuccess(new TransactionPurgeResponse(id, request.getSequence()), readTime() - now);
                });
                return null;
            }

            final FrontendTransaction tx = transactions.get(id);
            if (tx == null) {
                // This should never happen because the purge callback removes the transaction and puts it into
                // purged transactions in one go. If it does, we warn about the situation and
                LOG.warn("{}: transaction {} not tracked in {}, but not present in active transactions", persistenceId,
                    id, purgedTransactions);
                purgedTransactions.add(Range.singleton(ul));
                return new TransactionPurgeResponse(id, request.getSequence());
            }

            tree.purgeTransaction(id, () -> {
                purgedTransactions.add(Range.singleton(ul));
                transactions.remove(id);
                LOG.debug("{}: finished purging transaction {}", persistenceId(), id);
                envelope.sendSuccess(new TransactionPurgeResponse(id, request.getSequence()), readTime() - now);
            });
            return null;
        }

        if (purgedTransactions.contains(ul)) {
            LOG.warn("{}: Request {} is contained purged transactions {}", persistenceId, request, purgedTransactions);
            throw new DeadTransactionException(purgedTransactions);
        }
        final Boolean closed = closedTransactions.get(ul);
        if (closed != null) {
            final boolean successful = closed.booleanValue();
            LOG.debug("{}: Request {} refers to a {} transaction", persistenceId, request, successful ? "successful"
                    : "failed");
            throw new ClosedTransactionException(successful);
        }

        FrontendTransaction tx = transactions.get(id);
        if (tx == null) {
            // The transaction does not exist and we are about to create it, check sequence number
            if (request.getSequence() != 0) {
                LOG.debug("{}: no transaction state present, unexpected request {}", persistenceId(), request);
                throw UNSEQUENCED_START;
            }

            tx = createTransaction(request, id);
            transactions.put(id, tx);
        } else {
            final Optional<TransactionSuccess<?>> maybeReplay = tx.replaySequence(request.getSequence());
            if (maybeReplay.isPresent()) {
                final TransactionSuccess<?> replay = maybeReplay.get();
                LOG.debug("{}: envelope {} replaying response {}", persistenceId(), envelope, replay);
                return replay;
            }
        }

        return tx.handleRequest(request, envelope, now);
    }

    void destroy(final long sequence, final RequestEnvelope envelope, final long now) {
        LOG.debug("{}: closing history {}", persistenceId(), getIdentifier());
        tree.closeTransactionChain(getIdentifier(), () -> {
            envelope.sendSuccess(new LocalHistorySuccess(getIdentifier(), sequence), readTime() - now);
        });
    }

    void purge(final long sequence, final RequestEnvelope envelope, final long now) {
        LOG.debug("{}: purging history {}", persistenceId(), getIdentifier());
        tree.purgeTransactionChain(getIdentifier(), () -> {
            envelope.sendSuccess(new LocalHistorySuccess(getIdentifier(), sequence), readTime() - now);
        });
    }

    private FrontendTransaction createTransaction(final TransactionRequest<?> request, final TransactionIdentifier id)
            throws RequestException {
        if (request instanceof CommitLocalTransactionRequest) {
            LOG.debug("{}: allocating new ready transaction {}", persistenceId(), id);
            return createReadyTransaction(id, ((CommitLocalTransactionRequest) request).getModification());
        }
        if (request instanceof AbstractReadTransactionRequest) {
            if (((AbstractReadTransactionRequest<?>) request).isSnapshotOnly()) {
                LOG.debug("{}: allocatint new open snapshot {}", persistenceId(), id);
                return createOpenSnapshot(id);
            }
        }

        LOG.debug("{}: allocating new open transaction {}", persistenceId(), id);
        return createOpenTransaction(id);
    }

    abstract FrontendTransaction createOpenSnapshot(TransactionIdentifier id) throws RequestException;

    abstract FrontendTransaction createOpenTransaction(TransactionIdentifier id) throws RequestException;

    abstract FrontendTransaction createReadyTransaction(TransactionIdentifier id, DataTreeModification mod)
        throws RequestException;

    abstract ShardDataTreeCohort createReadyCohort(TransactionIdentifier id, DataTreeModification mod);

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues().add("identifier", getIdentifier())
                .add("persistenceId", persistenceId).add("transactions", transactions).toString();
    }
}
