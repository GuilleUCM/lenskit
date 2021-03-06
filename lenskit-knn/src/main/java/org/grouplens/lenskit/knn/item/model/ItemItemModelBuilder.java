/*
 * LensKit, an open source recommender systems toolkit.
 * Copyright 2010-2013 Regents of the University of Minnesota and contributors
 * Work on LensKit has been funded by the National Science Foundation under
 * grants IIS 05-34939, 08-08692, 08-12148, and 10-17697.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */
package org.grouplens.lenskit.knn.item.model;

import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import org.grouplens.lenskit.core.Transient;
import org.grouplens.lenskit.knn.item.ItemSimilarity;
import org.grouplens.lenskit.knn.item.ModelSize;
import org.grouplens.lenskit.scored.ScoredId;
import org.grouplens.lenskit.transform.threshold.Threshold;
import org.grouplens.lenskit.util.ScoredItemAccumulator;
import org.grouplens.lenskit.util.TopNScoredItemAccumulator;
import org.grouplens.lenskit.util.UnlimitedScoredItemAccumulator;
import org.grouplens.lenskit.vectors.SparseVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Build an item-item CF model from rating data.
 * This builder takes a very simple approach. It does not allow for vector
 * normalization and truncates on the fly.
 *
 * @author <a href="http://www.grouplens.org">GroupLens Research</a>
 */
@NotThreadSafe
public class ItemItemModelBuilder implements Provider<ItemItemModel> {
    private static final Logger logger = LoggerFactory.getLogger(ItemItemModelBuilder.class);

    private final ItemSimilarity itemSimilarity;
    private final ItemItemBuildContext buildContext;
    private final Threshold threshold;
    private final NeighborIterationStrategy neighborStrategy;
    private final int modelSize;

    @Inject
    public ItemItemModelBuilder(@Transient ItemSimilarity similarity,
                                @Transient ItemItemBuildContext context,
                                @Transient Threshold thresh,
                                @Transient NeighborIterationStrategy nbrStrat,
                                @ModelSize int size) {
        itemSimilarity = similarity;
        buildContext = context;
        threshold = thresh;
        neighborStrategy = nbrStrat;
        modelSize = size;
    }

    @Override
    public SimilarityMatrixModel get() {
        logger.debug("building item-item model");
        logger.debug("using similarity function {}", itemSimilarity);
        logger.debug("similarity function is {}",
                     itemSimilarity.isSparse() ? "sparse" : "non-sparse");
        logger.debug("similarity function is {}",
                     itemSimilarity.isSymmetric() ? "symmetric" : "non-symmetric");

        Accumulator accumulator = new Accumulator(buildContext.getItems(), threshold, modelSize);

        LongSortedSet allItems = buildContext.getItems();
        final int nitems = allItems.size();
        LongIterator outer = allItems.iterator();

        Stopwatch timer = new Stopwatch();
        timer.start();
        int ndone = 0;
        while (outer.hasNext()) {
            ndone += 1;
            final long itemId1 = outer.nextLong();
            logger.trace("computing similarities for item {} ({} of {})",
                         itemId1, ndone, nitems);
            SparseVector vec1 = buildContext.itemVector(itemId1);

            LongIterator itemIter = neighborStrategy.neighborIterator(buildContext, itemId1,
                                                                      itemSimilarity.isSymmetric());

            while (itemIter.hasNext()) {
                long itemId2 = itemIter.nextLong();
                if (itemId1 != itemId2) {
                    SparseVector vec2 = buildContext.itemVector(itemId2);
                    double sim = itemSimilarity.similarity(itemId1, vec1, itemId2, vec2);
                    accumulator.put(itemId1, itemId2, sim);
                    if (itemSimilarity.isSymmetric()) {
                        accumulator.put(itemId2, itemId1, sim);
                    }
                }
            }

            if (ndone % 100 == 0) {
                logger.debug("computed {} of {} model rows ({}s/row)",
                             ndone, nitems,
                             String.format("%.3f", timer.elapsed(TimeUnit.MILLISECONDS) * 0.001 / ndone));
            }
        }
        timer.stop();
        logger.info("built model for {} items in {}", ndone, timer);

        return accumulator.build();
    }

    static class Accumulator {

        private final Threshold threshold;
        private Long2ObjectMap<ScoredItemAccumulator> rows;
        private final LongSortedSet itemUniverse;

        public Accumulator(LongSortedSet entities, Threshold threshold, int modelSize) {
            logger.debug("Using simple accumulator with modelSize {} for {} items", modelSize, entities.size());
            this.threshold = threshold;
            itemUniverse = entities;
            rows = new Long2ObjectOpenHashMap<ScoredItemAccumulator>(entities.size());

            for (long itemId : itemUniverse) {
               if (modelSize == 0) {
                   rows.put(itemId, new UnlimitedScoredItemAccumulator());
               } else {
                   rows.put(itemId, new TopNScoredItemAccumulator(modelSize));
               }
            }
        }

        public void put(long i, long j, double sim) {
            Preconditions.checkState(rows != null, "model already built");

            if (!threshold.retain(sim)) {
                return;
            }

            ScoredItemAccumulator q = rows.get(i);
            q.put(j, sim);
        }

        public SimilarityMatrixModel build() {
            Long2ObjectMap<List<ScoredId>> data = new Long2ObjectOpenHashMap<List<ScoredId>>(rows.size());
            for (Long2ObjectMap.Entry<ScoredItemAccumulator> row : rows.long2ObjectEntrySet()) {
                List<ScoredId> similarities = row.getValue().finish();
                data.put(row.getLongKey(), similarities);
            }
            SimilarityMatrixModel model = new SimilarityMatrixModel(itemUniverse, data);
            rows = null;  // Mark that this model has already been built.
            return model;
        }
    }

}
