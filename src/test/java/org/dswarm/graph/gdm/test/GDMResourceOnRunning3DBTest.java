package org.dswarm.graph.gdm.test;

import org.dswarm.graph.test.Neo4jRunningDBWrapper;

/**
 *
 * @author tgaengler
 *
 */
public class GDMResourceOnRunning3DBTest extends GDMResource3Test {

	public GDMResourceOnRunning3DBTest() {

		super(new Neo4jRunningDBWrapper(), "running");
	}
}
