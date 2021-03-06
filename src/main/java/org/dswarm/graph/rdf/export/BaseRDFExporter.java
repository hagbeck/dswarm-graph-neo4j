/**
 * This file is part of d:swarm graph extension.
 *
 * d:swarm graph extension is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * d:swarm graph extension is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with d:swarm graph extension.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.dswarm.graph.rdf.export;

import java.util.HashMap;
import java.util.Map;

import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.index.NamespaceIndex;
import org.dswarm.graph.model.GraphStatics;
import org.dswarm.common.rdf.utils.RDFUtils;
import org.dswarm.graph.read.RelationshipHandler;
import org.dswarm.graph.utils.GraphUtils;

/**
 * @author polowins
 * @author tgaengler
 */
public abstract class BaseRDFExporter implements RDFExporter {

	private static final Logger				LOG								= LoggerFactory.getLogger(BaseRDFExporter.class);

	protected final RelationshipHandler relationshipHandler;

	protected final GraphDatabaseService database;

	private final NamespaceIndex namespaceIndex;

	protected Dataset dataset;

	private long processedStatements = 0;

	protected long successfullyProcessedStatements = 0;

	protected static final int JENA_MODEL_WARNING_SIZE = 1000000;

	// TODO: TransactionHandler
	public BaseRDFExporter(final GraphDatabaseService databaseArg, final NamespaceIndex namespaceIndexArg) {

		database = databaseArg;
		namespaceIndex = namespaceIndexArg;
		relationshipHandler = new CBDRelationshipHandler();
	}

	@Override
	public long countStatements() {

		return RDFUtils.determineDatasetSize(dataset);
	}

	private class CBDRelationshipHandler implements RelationshipHandler {

		// TODO: maybe a hash map is not appropriated for bigger exports

		final Map<Long, Resource>   bnodes    = new HashMap<>();
		final Map<String, Resource> resources = new HashMap<>();

		@Override
		public void handleRelationship(final Relationship rel) throws DMPGraphException {

			processedStatements++;

			// data model

			final String prefixedDataModelURI = (String) rel.getProperty(GraphStatics.DATA_MODEL_PROPERTY, null);

			if (prefixedDataModelURI == null) {

				final String message = String.format("data model URI can't be null (relationship id = '%s')", rel.getId());

				BaseRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			final String dataModelURI = namespaceIndex.createFullURI(prefixedDataModelURI);
			final Model model;

			if (dataset.containsNamedModel(dataModelURI)) {

				model = dataset.getNamedModel(dataModelURI);
			} else {

				model = ModelFactory.createDefaultModel();

				dataset.addNamedModel(dataModelURI, model);
			}

			if (model == null) {

				final String message = String.format("RDF model for graph '%s' ('%s') can't be null (relationship id = '%s')", dataModelURI, prefixedDataModelURI, rel.getId());

				BaseRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			// subject

			final Node subjectNode = rel.getStartNode();
			final NodeType subjectNodeType = GraphUtils.determineNodeType(subjectNode);

			final Resource subjectResource;

			switch (subjectNodeType) {

				case Resource:
				case TypeResource:

					final String prefixedSubjectURI = (String) subjectNode.getProperty(GraphStatics.URI_PROPERTY, null);

					if (prefixedSubjectURI == null) {

						final String message = "subject URI can't be null";

						BaseRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}

					final String fullSubjectURI = namespaceIndex.createFullURI(prefixedSubjectURI);
					subjectResource = createResourceFromURI(fullSubjectURI, model);

					break;
				case BNode:
				case TypeBNode:

					final long subjectId = subjectNode.getId();
					subjectResource = createResourceFromBNode(subjectId, model);

					break;
				default:

					final String message = "subject node type can only be a resource (or type resource) or bnode (or type bnode)";

					BaseRDFExporter.LOG.error(message);

					throw new DMPGraphException(message);
			}

			// predicateURI

			final String prefixedPredicate = rel.getType().name();
			final String predicateURI = namespaceIndex.createFullURI(prefixedPredicate);
			//.getProperty(GraphStatics.URI_PROPERTY, null);
			final Property predicate = model.createProperty(predicateURI);

			// object

			final Node objectNode = rel.getEndNode();
			final NodeType objectNodeType = GraphUtils.determineNodeType(objectNode);

			final RDFNode objectRDFNode;

			switch (objectNodeType) {

				case Resource:
				case TypeResource:

					final String prefixedObjectURI = (String) objectNode.getProperty(GraphStatics.URI_PROPERTY, null);

					if (prefixedObjectURI == null) {

						final String message = "object URI can't be null";

						BaseRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}
					final String fullObjectURI = namespaceIndex.createFullURI(prefixedObjectURI);
					objectRDFNode = createResourceFromURI(fullObjectURI, model);

					break;
				case BNode:
				case TypeBNode:

					final long objectId = objectNode.getId();

					objectRDFNode = createResourceFromBNode(objectId, model);

					break;
				case Literal:

					final String object = (String) objectNode.getProperty(GraphStatics.VALUE_PROPERTY, null);

					if (object == null) {

						final String message = "object value can't be null";

						BaseRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}

					if (objectNode.hasProperty(GraphStatics.DATATYPE_PROPERTY)) {

						final String literalType = (String) objectNode.getProperty(GraphStatics.DATATYPE_PROPERTY, null);

						if (literalType != null) {

							// object is a typed literal node

							objectRDFNode = model.createTypedLiteral(object, literalType);

							break;
						}
					}

					// object is an untyped literal node

					objectRDFNode = model.createLiteral(object);

					break;
				default:

					final String message = String.format("unknown node type %s for object node", objectNodeType.getName());

					BaseRDFExporter.LOG.error(message);

					throw new DMPGraphException(message);
			}

			if (subjectResource == null || predicate == null || objectRDFNode == null) {

				final String message = String.format("couldn't determine the complete statement (subject-predicateURI-object + data model) for relationship '%s'", rel.getId());

				BaseRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			model.add(subjectResource, predicate, objectRDFNode);

			successfullyProcessedStatements++;
		}

		private Resource createResourceFromBNode(final long bnodeId, final Model model) {

			if (!bnodes.containsKey(bnodeId)) {

				bnodes.put(bnodeId, model.createResource());
			}

			return bnodes.get(bnodeId);
		}

		private Resource createResourceFromURI(final String uri, final Model model) {

			if (!resources.containsKey(uri)) {

				resources.put(uri, model.createResource(uri));
			}

			return resources.get(uri);
		}
	}

	@Override
	public long processedStatements() {

		return processedStatements;
	}

	@Override
	public long successfullyProcessedStatements() {

		return successfullyProcessedStatements;
	}
}
