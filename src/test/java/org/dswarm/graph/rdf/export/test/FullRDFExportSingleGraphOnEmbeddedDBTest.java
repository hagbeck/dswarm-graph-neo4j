package org.dswarm.graph.rdf.export.test;

import org.dswarm.graph.test.Neo4jEmbeddedDBWrapper;

/**
 * @author polowins
 * @author tgaengler
 */
public class FullRDFExportSingleGraphOnEmbeddedDBTest extends FullRDFExportSingleGraphTest {

	public FullRDFExportSingleGraphOnEmbeddedDBTest() {

		super(new Neo4jEmbeddedDBWrapper("/ext"), "embedded");
	}
}