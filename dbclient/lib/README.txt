Put these JARs into this directory (they are not part of the repository):

- jfiltra-dbclient.jar   built by dbclient/script/compile.sh
- log4j-api-2.26.1.jar and log4j-core-2.26.1.jar (the same as in client/lib)
- the JDBC driver of your database, for example
    PostgreSQL       postgresql-42.x.jar
    MySQL            mysql-connector-j-9.x.jar
    MariaDB          mariadb-java-client-3.x.jar
    Oracle           ojdbc8-*.jar (Java 8) or ojdbc11-*.jar (Java 11+)

All JARs in this directory are on the classpath.
