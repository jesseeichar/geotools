package org.geotools.data.ingres;

import org.geotools.jdbc.JDBCLobTest;
import org.geotools.jdbc.JDBCLobTestSetup;

/**
 * 
 *
 * @source $URL$
 */
public class IngresLobTest extends JDBCLobTest {

    @Override
    protected JDBCLobTestSetup createTestSetup() {
        return new IngresLobTestSetup(new IngresTestSetup());
    }

}
