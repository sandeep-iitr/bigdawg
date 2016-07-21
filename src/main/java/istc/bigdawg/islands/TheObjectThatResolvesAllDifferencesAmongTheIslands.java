package istc.bigdawg.islands;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import istc.bigdawg.accumulo.AccumuloHandler;
import istc.bigdawg.catalog.Catalog;
import istc.bigdawg.catalog.CatalogViewer;
import istc.bigdawg.exceptions.BigDawgCatalogException;
import istc.bigdawg.exceptions.BigDawgException;
import istc.bigdawg.exceptions.UnsupportedIslandException;
import istc.bigdawg.executor.QueryResult;
import istc.bigdawg.islands.IslandsAndCast.Scope;
import istc.bigdawg.islands.Accumulo.AccumuloD4MParser;
import istc.bigdawg.islands.SStore.SStoreQueryParser;
import istc.bigdawg.islands.SciDB.AFLPlanParser;
import istc.bigdawg.islands.SciDB.AFLQueryGenerator;
import istc.bigdawg.islands.SciDB.AFLQueryPlan;
import istc.bigdawg.islands.SciDB.operators.SciDBIslandJoin;
import istc.bigdawg.islands.operators.Join;
import istc.bigdawg.islands.operators.Join.JoinType;
import istc.bigdawg.islands.operators.Operator;
import istc.bigdawg.islands.relational.SQLPlanParser;
import istc.bigdawg.islands.relational.SQLQueryGenerator;
import istc.bigdawg.islands.relational.SQLQueryPlan;
import istc.bigdawg.islands.relational.operators.SQLIslandJoin;
import istc.bigdawg.postgresql.PostgreSQLConnectionInfo;
import istc.bigdawg.postgresql.PostgreSQLHandler;
import istc.bigdawg.properties.BigDawgConfigProperties;
import istc.bigdawg.query.ConnectionInfo;
import istc.bigdawg.query.DBHandler;
import istc.bigdawg.scidb.SciDBConnectionInfo;
import istc.bigdawg.scidb.SciDBHandler;
import istc.bigdawg.signature.builder.ArraySignatureBuilder;
import istc.bigdawg.signature.builder.RelationalSignatureBuilder;
import istc.bigdawg.sstore.SStoreSQLConnectionInfo;
import istc.bigdawg.sstore.SStoreSQLHandler;
import net.sf.jsqlparser.JSQLParserException;

/**
 * @author Jack
 * 
 * This class should contain only static objects and functions. 
 * It is intended to contain all functions that do different things when the island context is different.
 * Island writers should go through each of these functions to add supports for their islands
 * 
 * NOTE: all relational island queries are assumed to be base in PostgreSQL. 
 *       This assumption needs to change as new engines join Relational Island 
 *
 */
public class TheObjectThatResolvesAllDifferencesAmongTheIslands {
	
	public static enum Engine {PostgreSQL, SciDB, SStore};
	
	public static final int  psqlSchemaHandlerDBID = BigDawgConfigProperties.INSTANCE.getPostgresSchemaServerDBID();
	public static final int  scidbSchemaHandlerDBID = BigDawgConfigProperties.INSTANCE.getSciDBSchemaServerDBID();
	public static final int  sstoreDBID = BigDawgConfigProperties.INSTANCE.getSStoreDBID();
	
	private static final Pattern predicatePattern = Pattern.compile("(?<=\\()([^\\(^\\)]+)(?=\\))");
	
	public static List<String> sqlEngineTokenList = new ArrayList<>();
	
	static {
		sqlEngineTokenList.add("postgres");
		sqlEngineTokenList.add("sstore");
	}
	/**
	 * For CrossIslandQueryPlan
	 * Determines whether an island is implemented
	 * @param scope
	 * @return
	 */
	public static boolean isOperatorBasedIsland(Scope scope) throws UnsupportedIslandException {
		switch (scope) {
		case ARRAY:
		case RELATIONAL:
			return true;
		case DOCUMENT:
		case GRAPH:
		case KEYVALUE:
		case STREAM:
		case TEXT:
			return false;
		default:
			throw new UnsupportedIslandException(scope, "isOperatorBasedIsland");
		}
	}
	
	/**
	 * Used in CatalogViewer
	 * @param engineString
	 * @return
	 * @throws BigDawgException
	 */
	public static Engine getEngineEnum(String engineString) throws BigDawgException {
		if (engineString.startsWith(Engine.PostgreSQL.name()))
			return Engine.PostgreSQL;
		else if (engineString.startsWith(Engine.SciDB.name()))
			return Engine.SciDB;
		else if (engineString.startsWith(Engine.SStore.name()))
			return Engine.SStore;
		else {
			throw new BigDawgException("Unsupported engine: "+ engineString);
		}
	}
	
	/**
	 * Used in CatalogViewer
	 * @param cc
	 * @param e
	 * @param dbid
	 * @return
	 * @throws SQLException
	 * @throws BigDawgCatalogException
	 */
	public static ConnectionInfo getQConnectionInfo(Catalog cc, Engine e, int dbid) throws SQLException, BigDawgCatalogException {
		
		ConnectionInfo extraction = null;
		ResultSet rs2;
		
		if (e.equals(Engine.PostgreSQL)) {
			
			rs2 = cc.execRet("select dbid, eid, host, port, db.name as dbname, userid, password from catalog.databases db join catalog.engines e on db.engine_id = e.eid where dbid = "+dbid);
			if (rs2.next())
				extraction = new PostgreSQLConnectionInfo(rs2.getString("host"), rs2.getString("port"),rs2.getString("dbname"), rs2.getString("userid"), rs2.getString("password"));
		} else if (e.equals(Engine.SciDB)) {
			rs2 = cc.execRet("select dbid, db.engine_id, host, port, bin_path, userid, password "
							+ "from catalog.databases db "
							+ "join catalog.engines e on db.engine_id = e.eid "
							+ "join catalog.scidbbinpaths sp on db.engine_id = sp.eid where dbid = "+dbid);
			if (rs2.next())
				extraction = new SciDBConnectionInfo(rs2.getString("host"), rs2.getString("port"), rs2.getString("userid"), rs2.getString("password"), rs2.getString("bin_path"));
		} else if (e.equals(Engine.SStore)) {
			
			rs2 = cc.execRet("select dbid, eid, host, port, db.name as dbname, userid, password from catalog.databases db join catalog.engines e on db.engine_id = e.eid where dbid = "+dbid);
			if (rs2.next())
				extraction = new SStoreSQLConnectionInfo(rs2.getString("host"), rs2.getString("port"),rs2.getString("dbname"), rs2.getString("userid"), rs2.getString("password"));
			
		} else 
			throw new BigDawgCatalogException("This is not supposed to happen");
		
		if (extraction == null) {
			rs2.close();
			throw new BigDawgCatalogException("Connection Info Cannot Be Formulated: "+dbid);
		}
			
		if (rs2.next()) {
			throw new BigDawgCatalogException("Non-unique DBID: "+dbid);
		}
		
		rs2.close();
		
		return extraction;
	}
	
	/**
	 * For Executor.
	 * @param scope
	 * @return Instance of a query generator
	 * @throws BigDawgException 
	 */
	public static OperatorVisitor getQueryGenerator(Scope scope) throws BigDawgException {
		switch (scope) {
		case ARRAY:
			return new AFLQueryGenerator();
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return new SQLQueryGenerator();
		case STREAM:
			break;
		case TEXT:
			throw new BigDawgException("TEXT island does not support the concept of generator; getQueryGenerator");
		default:
			break;
		}
		throw new UnsupportedIslandException(scope, "getQueryGenerator");
	}
	
	/**
	 * For Catalog
	 * @param scope
	 * @return A string in the form of " AND scope_name = 'RELATIONAL' ", where RELATIONAL is replaced by the island reference in the Catalog 
	 * @throws UnsupportedIslandException
	 */
	public static String getCatalogIslandSelectionPredicate(Scope scope) throws UnsupportedIslandException {
		switch (scope) {
		case ARRAY:
			return " AND scope_name = \'ARRAY\' ";
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return " AND scope_name = \'RELATIONAL\' ";
		case STREAM:
			return " AND scope_name = \'STREAM\' ";
		case TEXT:
			return " AND scope_name = \'TEXT\' ";
		default:
			break;
		}
		throw new UnsupportedIslandException(scope, "getCatalogIslandSelectionPredicate");
	}
	
	/**
	 * For query planning, CrossIslandQueryNode
	 * This function is used to create temporary tables in schema engines, where the temporary tables represent intermediate results from other islands
	 * @param sourceScope
	 * @param children
	 * @param transitionSchemas
	 * @return
	 * @throws SQLException
	 * @throws BigDawgException 
	 */
	public static DBHandler createTableForPlanning(Scope sourceScope, Set<String> children, Map<String, String> transitionSchemas) throws SQLException, BigDawgException {

		DBHandler dbSchemaHandler = null;
		
		switch (sourceScope) {
		case ARRAY:
			dbSchemaHandler = new SciDBHandler(scidbSchemaHandlerDBID);
			for (String key : transitionSchemas.keySet()) 
				if (children.contains(key)) {
					((SciDBHandler)dbSchemaHandler).executeStatement(transitionSchemas.get(key));
					((SciDBHandler)dbSchemaHandler).commit();
				}
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			dbSchemaHandler = new PostgreSQLHandler((PostgreSQLConnectionInfo)CatalogViewer.getConnectionInfo(psqlSchemaHandlerDBID));
			for (String key : transitionSchemas.keySet()) 
				if (children.contains(key)) ((PostgreSQLHandler)dbSchemaHandler).executeStatementPostgreSQL(transitionSchemas.get(key));
			break;
		case STREAM:
			throw new BigDawgException("STREAM island does not support data immigration; createTableForPlanning");
		case TEXT:
			throw new BigDawgException("TEXT island does not support data immigration; createTableForPlanning");
		default:
			break;
		}
		if (dbSchemaHandler == null) throw new UnsupportedIslandException(sourceScope, "createTableForPlanning");
		return dbSchemaHandler;
	}
	
	/**
	 * For query planning, CrossIslandQueryNode
	 * This function is responsible for removing temporary tables created in the name of intermediate results from other islands. 
	 * @param sourceScope
	 * @param dbSchemaHandler
	 * @param children
	 * @param transitionSchemas
	 * @throws SQLException
	 * @throws BigDawgException 
	 */
	public static void removeTemporaryTableCreatedForPlanning(Scope sourceScope, DBHandler dbSchemaHandler, Set<String> children, Map<String, String> transitionSchemas) throws SQLException, BigDawgException {
		
		switch (sourceScope) {
		case ARRAY:
			for (String key : transitionSchemas.keySet()) 
				if (children.contains(key)) {
					dbSchemaHandler = new SciDBHandler(scidbSchemaHandlerDBID); // because now the code closes the connection forcefully each time
					((SciDBHandler)dbSchemaHandler).executeStatementAFL("remove("+key+")");
					((SciDBHandler)dbSchemaHandler).commit();
				}
			return;
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			for (String key : transitionSchemas.keySet()) 
				if (children.contains(key)) 
					((PostgreSQLHandler)dbSchemaHandler).executeStatementPostgreSQL("drop table "+key);
			return;
		case STREAM:
			throw new BigDawgException("STREAM island does not support data immigration; removeTemporaryTableCreatedForPlanning");
		case TEXT:
			throw new BigDawgException("TEXT island does not support data immigration; removeTemporaryTableCreatedForPlanning");
		default:
			break;
		}
		
		throw new UnsupportedIslandException(sourceScope, "removeTemporaryTableCreatedForPlanning");
	}
	
	/**
	 * For query planning, CrossIslandQueryNode
	 * @param scope
	 * @return
	 * @throws BigDawgException 
	 */
	public static Integer getSchemaEngineDBID(Scope scope) throws BigDawgException {
		
		switch (scope) {
		case ARRAY:
			return scidbSchemaHandlerDBID;
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return psqlSchemaHandlerDBID;
		case STREAM:
			throw new BigDawgException("STREAM island does not have a default SchemaEngine, getSchemaEngineDBID");
		case TEXT:
			throw new BigDawgException("TEXT island does not have a default SchemaEngine, getSchemaEngineDBID");
		default:
			break;
		}
		
		throw new UnsupportedIslandException(scope, "getSchemaEngineDBID");
	}
	
	/**
	 * For query planning, CrossIslandQueryNode 
	 * Construct a join operator using limited information; used in creating permutations 
	 * @param scope
	 * @param o1, left operator
	 * @param o2, right operator
	 * @param jt, type of join
	 * @param joinPred, join predicate, could be null
	 * @param isFilter, whether it originally present as a joinFilter and not joinPredicate (distinction unclear)
	 * @return
	 * @throws JSQLParserException 
	 * @throws BigDawgException 
	 * @throws Exception
	 */
	public static Join constructJoin (Scope scope, Operator o1, Operator o2, JoinType jt, List<String> joinPred, boolean isFilter) throws JSQLParserException, BigDawgException {
		
		switch (scope) {
		case ARRAY:
			if (joinPred == null) return new SciDBIslandJoin().construct(o1, o2, jt, null, isFilter);
			return new SciDBIslandJoin().construct(o1, o2, jt, String.join(", ", joinPred.stream().map(s -> s.replaceAll("[<>=]+", ", ")).collect(Collectors.toSet())), isFilter);
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			if (joinPred == null) return new SQLIslandJoin(o1, o2, jt, null, isFilter);
			return new SQLIslandJoin(o1, o2, jt, String.join(" AND ", joinPred), isFilter);
		case STREAM:
			throw new BigDawgException("STREAM island does not support the concept of Join; constructJoin");
		case TEXT:
			throw new BigDawgException("TEXT island does not support the concept of Join; constructJoin");
		default:
			break;
		}
		
		throw new UnsupportedIslandException(scope, "constructJoin");
	}
	
	/**
	 * For query planning, CrossIslandQueryNode
	 * Used in signature creation. Sig2 means all tables involved in a query.
	 * @param scope
	 * @param dbSchemaHandler
	 * @param queryString
	 * @param objs
	 * @return
	 * @throws Exception
	 */
	public static Operator generateOperatorTreesAndAddDataSetObjectsSignature(Scope scope, DBHandler dbSchemaHandler, String queryString, List<String> objs) throws Exception {
		
		Operator root = null;
		
		switch (scope) {
		case ARRAY:
			AFLQueryPlan arrayQueryPlan = AFLPlanParser.extractDirect((SciDBHandler)dbSchemaHandler, queryString);
			root = arrayQueryPlan.getRootNode();
			objs.addAll(ArraySignatureBuilder.sig2(queryString));
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			SQLQueryPlan relQueryPlan = SQLPlanParser.extractDirectFromPostgreSQL((PostgreSQLHandler)dbSchemaHandler, queryString);
			root = relQueryPlan.getRootNode();
			objs.addAll(RelationalSignatureBuilder.sig2(queryString));
		case STREAM:
			throw new BigDawgException("STREAM island does not support signature; generateOperatorTreesAndAddDataSetObjectsSignature");
		case TEXT:
			throw new BigDawgException("TEXT island does not support signature; generateOperatorTreesAndAddDataSetObjectsSignature");
		default:
			break;
		}
		
		if (root == null) throw new UnsupportedIslandException(scope, "generateOperatorTrees");
		return root;
	} 
	
	/**
	 * For query planning, CrossIslandQueryNode
	 * @return
	 * @throws BigDawgException 
	 */
	public static Set<String> splitPredicates(Scope scope, String predicates) throws BigDawgException {
		Set<String> results = new HashSet<>();

		String joinDelim = null;
		
		switch (scope) {
		case ARRAY:
			joinDelim = "[,=]";
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			joinDelim = "[=<>]+";
		case STREAM:
			throw new BigDawgException("STREAM island does not participate in splitPredicates function; splitPredicates");
		case TEXT:
			throw new BigDawgException("TEXT island does not participate in splitPredicates function; splitPredicates");
		default:
			break;
		}
		
		if (joinDelim == null) throw new UnsupportedIslandException(scope, "splitPredicates");

		Matcher m = predicatePattern.matcher(predicates);
		while (m.find()){
			String current = m.group().replace(" ", "");
			String[] filters = current.split(joinDelim);
			Arrays.sort(filters);
			String result = String.join(joinDelim, filters);
			results.add(result);
		}
		return results;
	}
	
	/**
	 * For monitoring, signature
	 * Sig3 consist of constants and other literals used in the query.
	 * @param scope
	 * @param query
	 * @return
	 * @throws IOException
	 * @throws BigDawgException 
	 */
	public static List<String> getLiteralsAndConstantsSignature(Scope scope, String query) throws IOException, BigDawgException {
		
		switch (scope) {
		case ARRAY:
			return ArraySignatureBuilder.sig3(query);
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return RelationalSignatureBuilder.sig3(query);
		case STREAM:
			throw new BigDawgException("STREAM island does not support signature; getLiteralsAndConstantsSignature");
		case TEXT:
			throw new BigDawgException("TEXT island does not support signature; getLiteralsAndConstantsSignature");
		default:
			break;
		}

		throw new UnsupportedIslandException(scope, "getDataSetObjectsSignature");
	}
	
	/**
	 * For monitoring, signature
	 * @param scope
	 * @param query
	 * @return
	 * @throws UnsupportedIslandException
	 */
	public static String getIslandStyleQuery(Scope scope, String query) throws UnsupportedIslandException {
		
		switch (scope) {
		case ARRAY:
			return String.format("bdarray(%s);", query);
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return String.format("bdrel(%s);", query);
		case STREAM:
			return String.format("bdstream(%s);", query);
		case TEXT:
			return String.format("bdtext(%s);", query);
		default:
			break;
		}
			
		throw new UnsupportedIslandException(scope, "getIslandStyleQuery");
	}
	
	/**
	 * For CrossIslandCastNode
	 * @param scope
	 * @param name
	 * @param schemaCreationQuery
	 * @return
	 * @throws BigDawgException 
	 */
	public static String getCreationQueryForCast(Scope scope, String name, String schemaCreationQuery) throws BigDawgException {
		
		switch (scope) {
		case ARRAY:
			return String.format("CREATE ARRAY %s %s", name, schemaCreationQuery);
		case CAST:
			break;
		case DOCUMENT:
			break;
		case GRAPH:
			break;
		case KEYVALUE:
			break;
		case RELATIONAL:
			return String.format("CREATE TABLE %s %s", name, schemaCreationQuery);
		case STREAM:
			throw new BigDawgException("STREAM Island does not allow you to create new tables; getCreationQueryForCast");
		case TEXT:
			throw new BigDawgException("TEXT Island does not allow you to create new tables; getCreationQueryForCast");
		default:
			break;
		}
		
		throw new UnsupportedIslandException(scope, "getCreationQuery");
	}
	
	
	//// island specific
	
	/**
	 * For Relational Island only.
	 * @param e
	 * @param tableName
	 * @return
	 * @throws SQLException
	 * @throws BigDawgException
	 */
	public static String getRelationalIslandCreateTableString(String tableName) throws SQLException, BigDawgException  {
		int dbid;

		if (tableName.toLowerCase().startsWith("bigdawgtag_")) dbid = psqlSchemaHandlerDBID;
		else dbid = CatalogViewer.getDbsOfObject(tableName, sqlEngineTokenList).get(0);
		
		Connection con = PostgreSQLHandler.getConnection((PostgreSQLConnectionInfo)CatalogViewer.getConnectionInfo(dbid));
		return PostgreSQLHandler.getCreateTable(con, tableName).replaceAll("\\scharacter[\\(]", " char(");
	}
	
	//// Operator free options
	
	public static QueryResult runOperatorFreeIslandQuery(CrossIslandNonOperatorNode node) throws Exception {
		
		switch (node.sourceScope) {
		
		case DOCUMENT:
		case GRAPH:
		case KEYVALUE:
			throw new UnsupportedIslandException(node.sourceScope, "runOperatorFreeIslandQuery");
		case STREAM:
			
			List<String> ssparsed = (new SStoreQueryParser()).parse(node.getQueryString());
			return (new SStoreSQLHandler(sstoreDBID)).executePreparedStatement((new SStoreSQLHandler(sstoreDBID)).getConnection(), ssparsed);
			
		case TEXT:
			List<String> aparsed = (new AccumuloD4MParser()).parse(node.getQueryString());
			System.out.printf("TEXT Island Accumulot query: %s; parsed arguments: %s\n", node.queryString, aparsed);
			return AccumuloHandler.executeAccumuloShellScript(aparsed);
//			throw new BigDawgException("TEXT Island is still under construction and you can't issue query to it yet; runOperatorFreeIslandQuery");
		case RELATIONAL:
		case ARRAY:
		default:
			throw new BigDawgException("Unapplicable island for runOperatorFreeIslandQuery: "+node.sourceScope.name());
		}
		
	}
}