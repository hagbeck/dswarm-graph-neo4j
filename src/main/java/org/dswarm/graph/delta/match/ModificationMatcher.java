package org.dswarm.graph.delta.match;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.dswarm.graph.delta.DeltaState;
import org.dswarm.graph.delta.match.mark.Marker;
import org.dswarm.graph.delta.match.model.ModificationEntity;
import org.neo4j.graphdb.GraphDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;

public abstract class ModificationMatcher<ENTITY extends ModificationEntity> extends Matcher<ENTITY> implements ModificationResultSet<ENTITY> {

	private static final Logger LOG = LoggerFactory.getLogger(ModificationMatcher.class);

	private Map<ENTITY, ENTITY> modifications;

	public ModificationMatcher(final Optional<? extends Collection<ENTITY>> existingEntitiesArg,
			final Optional<? extends Collection<ENTITY>> newEntitiesArg, final GraphDatabaseService existingResourceDBArg,
			final GraphDatabaseService newResourceDBArg, final String existingResourceURIArg, final String newResourceURIArg,
			final Marker<ENTITY> markerArg) {

		super(existingEntitiesArg, newEntitiesArg, existingResourceDBArg, newResourceDBArg, existingResourceURIArg, newResourceURIArg, markerArg);
	}

	@Override
	public void match() {

		getModifications();
		super.match();
		markNonMatchedPaths();
	}

	@Override
	public Map<ENTITY, ENTITY> getModifications() {

		if(!matchesCalculated) {

			modifications = new HashMap<>();
			matches = new HashSet<>();

			if(existingEntities.isPresent() && newEntities.isPresent()) {

				for (final Map.Entry<String, ENTITY> existingEntityEntry : existingEntities.get().entrySet()) {

					if (newEntities.get().containsKey(existingEntityEntry.getKey())) {

						final ENTITY existingEntity = existingEntityEntry.getValue();
						final ENTITY newEntity = newEntities.get().get(existingEntityEntry.getKey());

						if (existingEntity.getValue() != null && newEntity.getValue() != null && !existingEntity.getValue()
								.equals(newEntity.getValue())) {

							modifications.put(existingEntity, newEntity);
							matches.add(existingEntityEntry.getKey());
						}
					}
				}
			}

			matchesCalculated = true;
		}

		return modifications;
	}

	@Override
	protected void calculateMatches() {

		// nothing to do
	}

	@Override
	protected void markMatchedPaths() {

		ModificationMatcher.LOG.debug("mark matched paths in existing resource (modifications)");

		markPaths(getMatches(existingEntities), DeltaState.MODIFICATION, existingResourceDB, existingResourceURI);

		ModificationMatcher.LOG.debug("mark matched paths in new resource (modifications)");

		markPaths(getMatches(newEntities), DeltaState.MODIFICATION, newResourceDB, newResourceURI);
	}

	/**
	 * a.k.a. additions + deletions
	 */
	protected void markNonMatchedPaths() {

		ModificationMatcher.LOG.debug("mark non-matched paths in existing resource (deletions)");

		markPaths(getNonMatches(existingEntities), DeltaState.DELETION, existingResourceDB, existingResourceURI);

		ModificationMatcher.LOG.debug("mark non-matched paths in new resource (additions)");

		markPaths(getNonMatches(newEntities), DeltaState.ADDITION, newResourceDB, newResourceURI);
	}

}
