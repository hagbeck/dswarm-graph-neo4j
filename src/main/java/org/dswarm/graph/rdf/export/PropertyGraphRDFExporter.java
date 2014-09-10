package org.dswarm.graph.rdf.export;

import java.util.HashMap;
import java.util.Map;

import org.dswarm.graph.model.GraphStatics;
import org.dswarm.graph.NodeType;
import org.dswarm.graph.rdf.utils.RDFUtils;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.tooling.GlobalGraphOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.DatasetFactory;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.read.RelationshipHandler;
import org.dswarm.graph.utils.GraphUtils;

/**
 * @author polowins
 * @author tgaengler
 */
public class PropertyGraphRDFExporter implements RDFExporter {

	private static final Logger			LOG								= LoggerFactory.getLogger(PropertyGraphRDFExporter.class);

	private final RelationshipHandler	relationshipHandler;

	private final GraphDatabaseService	database;

	private Dataset						dataset;

	private long						processedStatements				= 0;

	private long						successfullyProcessedStatements	= 0;

	public PropertyGraphRDFExporter(final GraphDatabaseService databaseArg) {

		database = databaseArg;
		relationshipHandler = new CBDRelationshipHandler();
	}

	@Override
	public Dataset export() throws DMPGraphException {

		try(final Transaction tx = database.beginTx()) {

			/*
			 * // all nodes would also return endnodes without further outgoing relations final Iterable<Node> recordNodes;
			 * GlobalGraphOperations globalGraphOperations = GlobalGraphOperations.at(database); recordNodes =
			 * globalGraphOperations.getAllNodes();
			 */

			final GlobalGraphOperations globalGraphOperations = GlobalGraphOperations.at(database);

			// TODO: maybe slice this a bit, and deliver the whole graph in pieces
			// please also note that the Jena model implementation has its size limits (~1 mio statements (?) -> so one graph (of
			// one data resource) need to keep this size in mind)

			final Iterable<Relationship> relations = globalGraphOperations.getAllRelationships();

			if (relations == null) {

				tx.success();

				return null;
			}

			dataset = DatasetFactory.createMem();

			for (final Relationship recordNode : relations) {

				relationshipHandler.handleRelationship(recordNode);
			}

			tx.success();
		} catch (final Exception e) {

			final String mesage = "couldn't finish read RDF TX successfully";

			PropertyGraphRDFExporter.LOG.error(mesage, e);

			throw new DMPGraphException(mesage);
		}

		return dataset;
	}

	@Override
	public long countStatements() {

		return RDFUtils.determineDatasetSize(dataset);
	}

	private class CBDRelationshipHandler implements RelationshipHandler {

		// TODO: maybe a hash map is not appropriated for bigger exports

		final Map<Long, Resource>	bnodes		= new HashMap<Long, Resource>();
		final Map<String, Resource>	resources	= new HashMap<String, Resource>();

		@Override
		public void handleRelationship(final Relationship rel) throws DMPGraphException {

			processedStatements++;

			// data model

			final String dataModelURI = (String) rel.getProperty(GraphStatics.DATA_MODEL_PROPERTY, null);

			if (dataModelURI == null) {

				final String message = "data model URI can't be null (relationship id = '" + rel.getId() + "'";

				PropertyGraphRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			final Model model;

			if (dataset.containsNamedModel(dataModelURI)) {

				model = dataset.getNamedModel(dataModelURI);
			} else {

				model = ModelFactory.createDefaultModel();

				dataset.addNamedModel(dataModelURI, model);
			}

			if (model == null) {

				final String message = "RDF model for graph '" + dataModelURI + "' can't be null (relationship id = '" + rel.getId() + "'";

				PropertyGraphRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			// subject

			final Node subjectNode = rel.getStartNode();
			final NodeType subjectNodeType = GraphUtils.determineNodeType(subjectNode);

			final Resource subjectResource;

			switch (subjectNodeType) {

				case Resource:
				case TypeResource:

					final String subjectURI = (String) subjectNode.getProperty(GraphStatics.URI_PROPERTY, null);

					if (subjectURI == null) {

						final String message = "subject URI can't be null";

						PropertyGraphRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}

					subjectResource = createResourceFromURI(subjectURI, model);

					break;
				case BNode:
				case TypeBNode:

					final long subjectId = subjectNode.getId();
					subjectResource = createResourceFromBNode(subjectId, model);

					break;
				default:

					final String message = "subject node type can only be a resource (or type resource) or bnode (or type bnode)";

					PropertyGraphRDFExporter.LOG.error(message);

					throw new DMPGraphException(message);
			}

			// predicate

			final String predicate = rel.getType().name();
			// .getProperty(GraphStatics.URI_PROPERTY, null);
			final Property predicateProperty = model.createProperty(predicate);

			// object

			final Node objectNode = rel.getEndNode();
			final NodeType objectNodeType = GraphUtils.determineNodeType(objectNode);

			final RDFNode objectRDFNode;

			switch (objectNodeType) {

				case Resource:
				case TypeResource:

					final String objectURI = (String) objectNode.getProperty(GraphStatics.URI_PROPERTY, null);

					if (objectURI == null) {

						final String message = "object URI can't be null";

						PropertyGraphRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}

					objectRDFNode = createResourceFromURI(objectURI, model);

					break;
				case BNode:
				case TypeBNode:

					final long objectId = objectNode.getId();

					objectRDFNode = createResourceFromBNode(objectId, model);

					break;
				case Literal:

					final Node endNode = objectNode;
					final String object = (String) endNode.getProperty(GraphStatics.VALUE_PROPERTY, null);

					if (object == null) {

						final String message = "object value can't be null";

						PropertyGraphRDFExporter.LOG.error(message);

						throw new DMPGraphException(message);
					}

					if (endNode.hasProperty(GraphStatics.DATATYPE_PROPERTY)) {

						final String literalType = (String) endNode.getProperty(GraphStatics.DATATYPE_PROPERTY, null);

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

					final String message = "unknown node type " + objectNodeType.getName() + " for object node";

					PropertyGraphRDFExporter.LOG.error(message);

					throw new DMPGraphException(message);
			}

			if (subjectResource == null || predicateProperty == null || objectRDFNode == null) {

				final String message = "couldn't determine the complete statement (subject-predicate-object + data model) for relationship '"
						+ rel.getId() + "'";

				PropertyGraphRDFExporter.LOG.error(message);

				throw new DMPGraphException(message);
			}

			model.add(subjectResource, predicateProperty, objectRDFNode);

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
