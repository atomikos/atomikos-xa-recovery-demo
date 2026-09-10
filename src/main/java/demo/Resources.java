package demo;

import com.atomikos.jdbc.AtomikosDataSourceBean;
import io.github.rrobetti.xafault.XaScenarioEngine;
import io.github.rrobetti.xafault.jdbc.FaultInjectingJdbc;
import javax.sql.XADataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Builds the two enlisted resources. Each H2 {@link javax.sql.XADataSource}
 * is wrapped by j-xa-tester (https://github.com/rrobetti/j-xa-tester) before
 * being handed to Atomikos, so every XA call Atomikos makes against it can be
 * intercepted, recorded, and -- on a matching rule -- made to fail.
 */
final class Resources {
    private Resources() {}

    static AtomikosDataSourceBean atomikosDataSource(String resourceId, String dbPath, XaScenarioEngine engine) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setUrl("jdbc:h2:file:" + dbPath + ";DB_CLOSE_ON_EXIT=FALSE");

        XADataSource faultInjecting = FaultInjectingJdbc.wrap(h2, engine, resourceId);

        AtomikosDataSourceBean dataSource = new AtomikosDataSourceBean();
        dataSource.setUniqueResourceName(resourceId);
        dataSource.setXaDataSource(faultInjecting);
        dataSource.setMinPoolSize(1);
        dataSource.setMaxPoolSize(1);
        dataSource.setBorrowConnectionTimeout(30);
        return dataSource;
    }
}
