package org.dswarm.graph.gdm.parse;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.dswarm.graph.DMPGraphException;
import org.dswarm.graph.NodeType;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

import org.dswarm.graph.json.LiteralNode;
import org.dswarm.graph.json.Resource;
import org.dswarm.graph.json.ResourceNode;
import org.dswarm.graph.json.Statement;
import org.dswarm.graph.model.GraphStatics;

/**
 * @author tgaengler
 */
public class Neo4jGDMWProvenanceHandler implements GDMHandler {

	private static final Logger			LOG					= LoggerFactory.getLogger(Neo4jGDMWProvenanceHandler.class);

	private int							totalTriples		= 0;
	private int							addedNodes			= 0;
	private int							addedLabels			= 0;
	private int							addedRelationships	= 0;
	private int							sinceLastCommit		= 0;
	private int							i					= 0;
	private int							literals			= 0;

	private long						tick				= System.currentTimeMillis();
	private final GraphDatabaseService	database;
	private final Index<Node>			resources;
	private final Index<Node>			resourcesWProvenance;
	private final Index<Node>			resourceTypes;
	private final Index<Node>			values;
	private final Map<String, Node>		bnodes;
	private final Index<Relationship> statementHashes;
	private final Index<Relationship> statementUUIDsWProvenance;
	private final Map<Long, String>   nodeResourceMap;

	private Transaction tx;

	private final String resourceGraphURI;

	public Neo4jGDMWProvenanceHandler(final GraphDatabaseService database, final String resourceGraphURIArg) {

		this.database = database;
		tx = database.beginTx();

		LOG.debug("start write TX");

		resources = database.index().forNodes("resources");
		resourcesWProvenance = database.index().forNodes("resources_w_provenance");
		resourceTypes = database.index().forNodes("resource_types");
		values = database.index().forNodes("values");
		bnodes = new HashMap<String, Node>();
		statementHashes = database.index().forRelationships("statement_hashes");
		statementUUIDsWProvenance = database.index().forRelationships("statement_uuids_w_provenance");
		nodeResourceMap = new HashMap<Long, String>();

		resourceGraphURI = resourceGraphURIArg;
	}

	@Override
	public void handleStatement(final Statement st, final Resource r, final long index) {

		// utilise r for the resource property

		i++;

		// System.out.println("handle statement " + i + ": " + st.toString());

		try {

			final org.dswarm.graph.json.Node subject = st.getSubject();

			final org.dswarm.graph.json.Predicate predicate = st.getPredicate();
			final String predicateName = predicate.getUri();

			final org.dswarm.graph.json.Node object = st.getObject();

			final String statementUUID = st.getUUID();
			final Long order = st.getOrder();

			// Check index for subject
			// TODO: what should we do, if the subject is a resource type?
			Node subjectNode = determineNode(subject, false);

			if (subjectNode == null) {

				subjectNode = database.createNode();

				if (subject instanceof ResourceNode) {

					// subject is a resource node

					final String subjectURI = ((ResourceNode) subject).getUri();

					subjectNode.setProperty(GraphStatics.URI_PROPERTY, subjectURI);
					subjectNode.setProperty(GraphStatics.PROVENANCE_PROPERTY, resourceGraphURI);
					subjectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Resource.toString());
					resources.add(subjectNode, GraphStatics.URI, subjectURI);
					resourcesWProvenance.add(subjectNode, GraphStatics.URI_W_PROVENANCE, subjectURI + resourceGraphURI);
				} else {

					// subject is a blank node

					// note: can I expect an id here?
					bnodes.put("" + subject.getId(), subjectNode);
					subjectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.BNode.toString());
				}

				addedNodes++;
			}

			if (object instanceof LiteralNode) {

				literals++;

				final LiteralNode literal = (LiteralNode) object;
				final String value = literal.getValue();
				final Node objectNode = database.createNode();
				objectNode.setProperty(GraphStatics.VALUE_PROPERTY, value);
				objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Literal.toString());
				values.add(objectNode, GraphStatics.VALUE, value);

				final String resourceUri = addResourceProperty(subjectNode, subject, objectNode, r);

				addedNodes++;

				addRelationship(subjectNode, predicateName, objectNode, resourceUri, subject, r, statementUUID, order, index, subject.getType(), object.getType());
			} else { // must be Resource
						// Make sure object exists

				boolean isType = false;

				// add Label if this is a type entry
				if (predicateName.equals(RDF.type.getURI())) {

					addLabel(subjectNode, ((ResourceNode) object).getUri());

					isType = true;
				}

				// Check index for object
				Node objectNode = determineNode(object, isType);
				String resourceUri = null;

				if (objectNode == null) {

					objectNode = database.createNode();

					if (object instanceof ResourceNode) {

						// object is a resource node

						final String objectURI = ((ResourceNode) object).getUri();

						objectNode.setProperty(GraphStatics.URI_PROPERTY, objectURI);

						if (!isType) {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.Resource.toString());
						} else {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.TypeResource.toString());
							addLabel(objectNode, RDFS.Class.getURI());

							resourceTypes.add(objectNode, GraphStatics.URI, objectURI);
						}

						resources.add(objectNode, GraphStatics.URI, objectURI);
						resourcesWProvenance.add(objectNode, GraphStatics.URI_W_PROVENANCE, objectURI + resourceGraphURI);
					} else {

						// object is a blank node

						bnodes.put("" + object.getId(), objectNode);

						if (!isType) {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.BNode.toString());
							resourceUri = addResourceProperty(subjectNode, subject, objectNode, r);
						} else {

							objectNode.setProperty(GraphStatics.NODETYPE_PROPERTY, NodeType.TypeBNode.toString());
							addLabel(objectNode, RDFS.Class.getURI());
						}
					}

					addedNodes++;
				}

				addRelationship(subjectNode, predicateName, objectNode, resourceUri, subject, r, statementUUID, order, index, subject.getType(), object.getType());
			}

			totalTriples++;

			final long nodeDelta = totalTriples - sinceLastCommit;
			final long timeDelta = (System.currentTimeMillis() - tick) / 1000;

			if (nodeDelta >= 50000 || timeDelta >= 30) { // Commit every 50k operations or every 30 seconds

				tx.success();
				tx.close();
				tx = database.beginTx();

				sinceLastCommit = totalTriples;

				LOG.debug(totalTriples + " triples @ ~" + (double) nodeDelta / timeDelta + " triples/second.");

				tick = System.currentTimeMillis();
			}
		} catch (final Exception e) {

			LOG.error("couldn't finished write TX successfully", e);

			tx.failure();
			tx.close();
			LOG.debug("close a write TX");

			tx = database.beginTx();

			LOG.debug("start another write TX");

		} finally {

			// ???
		}
	}

	@Override
	public void closeTransaction() {

		LOG.debug("close write TX finally");

		tx.success();
		tx.close();
	}

	public int getCountedStatements() {

		return totalTriples;
	}

	public int getNodesAdded() {

		return addedNodes;
	}

	public int getRelationShipsAdded() {

		return addedRelationships;
	}

	public int getCountedLiterals() {

		return literals;
	}

	private void addLabel(final Node node, final String labelString) {

		final Label label = DynamicLabel.label(labelString);
		boolean hit = false;
		final Iterable<Label> labels = node.getLabels();
		final List<Label> labelList = new LinkedList<Label>();

		for (final Label lbl : labels) {

			if (label.equals(lbl)) {

				hit = true;
				break;
			}

			labelList.add(lbl);
		}

		if (!hit) {

			labelList.add(label);
			node.addLabel(label);
			addedLabels++;
		}
	}

	private Relationship addRelationship(final Node subjectNode, final String predicateName, final Node objectNode, final String resourceUri,
			final org.dswarm.graph.json.Node subject, final Resource resource, final String statementUUID, final Long order, final long index,
			final org.dswarm.graph.json.NodeType subjectNodeType, final org.dswarm.graph.json.NodeType objectNodeType) throws DMPGraphException {

		final StringBuffer sb = new StringBuffer();

		final String subjectIdentifier = getIdentifier(subjectNode, subjectNodeType);
		final String objectIdentifier = getIdentifier(objectNode, objectNodeType);

		sb.append(subjectNodeType.toString()).append(":").append(subjectIdentifier).append(" ").append(predicateName).append(" ")
				.append(objectNodeType.toString()).append(":").append(objectIdentifier).append(" ").append(resourceGraphURI);

		MessageDigest messageDigest = null;

		try {
			messageDigest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {

			throw new DMPGraphException("couldn't instantiate hash algo");
		}
		messageDigest.update(sb.toString().getBytes());
		final String hash = new String(messageDigest.digest());

		final Relationship rel;

		IndexHits<Relationship> hits = statementHashes.get(GraphStatics.HASH, hash);

		if (hits == null || !hits.hasNext()) {

			final RelationshipType relType = DynamicRelationshipType.withName(predicateName);
			rel = subjectNode.createRelationshipTo(objectNode, relType);

			final String finalStatementUUID;

			if(statementUUID == null) {

				finalStatementUUID = UUID.randomUUID().toString();
			} else {

				finalStatementUUID = statementUUID;
			}

			rel.setProperty(GraphStatics.UUID_PROPERTY, finalStatementUUID);

			if (order != null) {

				rel.setProperty(GraphStatics.ORDER_PROPERTY, order);
			}

			rel.setProperty(GraphStatics.INDEX_PROPERTY, index);

			// note: this property is not really necessary, since the uri is also the relationship type
			// rel.setProperty(GraphStatics.URI_PROPERTY, predicateName);
			rel.setProperty(GraphStatics.PROVENANCE_PROPERTY, resourceGraphURI);

			statementHashes.add(rel, GraphStatics.HASH, hash);
			statementUUIDsWProvenance.add(rel, GraphStatics.UUID_W_PROVENANCE, resourceGraphURI + "." + finalStatementUUID);

			addedRelationships++;

			addResourceProperty(subjectNode, subject, rel, resourceUri, resource);
		} else {

			rel = hits.next();
		}

		return rel;
	}

	private Node determineNode(final org.dswarm.graph.json.Node resource, final boolean isType) {

		final Node node;

		if (resource instanceof ResourceNode) {

			// resource node

			final IndexHits<Node> hits;

			if (!isType) {

				hits = resourcesWProvenance.get(GraphStatics.URI_W_PROVENANCE, ((ResourceNode) resource).getUri() + resourceGraphURI);
			} else {

				hits = resourceTypes.get(GraphStatics.URI, ((ResourceNode) resource).getUri());
			}

			if (hits != null && hits.hasNext()) {

				// node exists

				node = hits.next();

				return node;
			}

			return null;
		}

		if (resource instanceof LiteralNode) {

			// literal node - should never be the case

			return null;
		}

		// resource must be a blank node

		node = bnodes.get("" + resource.getId());

		return node;
	}

	private String addResourceProperty(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Node objectNode,
			final Resource resource) {

		final String resourceUri = determineResourceUri(subjectNode, subject, resource);

		if (resourceUri == null) {

			return null;
		}

		objectNode.setProperty(GraphStatics.RESOURCE_PROPERTY, resourceUri);

		return resourceUri;
	}

	private String addResourceProperty(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Relationship rel,
			final String resourceUri, final Resource resource) {

		final String finalResourceUri;

		if (resourceUri != null) {

			finalResourceUri = resourceUri;
		} else {

			finalResourceUri = determineResourceUri(subjectNode, subject, resource);
		}

		rel.setProperty(GraphStatics.RESOURCE_PROPERTY, finalResourceUri);

		return finalResourceUri;
	}

	private String determineResourceUri(final Node subjectNode, final org.dswarm.graph.json.Node subject, final Resource resource) {

		final Long nodeId = subjectNode.getId();

		final String resourceUri;

		if (nodeResourceMap.containsKey(nodeId)) {

			resourceUri = nodeResourceMap.get(nodeId);
		} else {

			if (subject instanceof ResourceNode) {

				resourceUri = ((ResourceNode) subject).getUri();
			} else {

				resourceUri = resource.getUri();
			}

			nodeResourceMap.put(nodeId, resourceUri);
		}

		return resourceUri;
	}

	private String getIdentifier(final Node node, final org.dswarm.graph.json.NodeType nodeType) {

		final String identifier;

		switch (nodeType) {

			case Resource:

				identifier = (String) node.getProperty(GraphStatics.URI_PROPERTY, null);

				break;
			case BNode:

				identifier = "" + node.getId();

				break;
			case Literal:

				identifier = (String) node.getProperty(GraphStatics.VALUE_PROPERTY, null);

				break;
			default:

				identifier = null;

				break;
		}

		return identifier;
	}
}
