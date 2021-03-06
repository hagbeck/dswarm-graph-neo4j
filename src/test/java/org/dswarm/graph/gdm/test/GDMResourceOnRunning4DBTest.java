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
package org.dswarm.graph.gdm.test;

import org.dswarm.graph.test.Neo4jRunningDBWrapper;

/**
 *
 * @author tgaengler
 *
 */
public class GDMResourceOnRunning4DBTest extends GDMResource4Test {

	public GDMResourceOnRunning4DBTest() {

		super(new Neo4jRunningDBWrapper(), "running");
	}
}
