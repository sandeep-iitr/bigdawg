package istc.bigdawg.packages;

import java.util.Map;
import java.util.Set;

import istc.bigdawg.plan.generators.SQLQueryGenerator;
import istc.bigdawg.plan.operators.Operator;
import istc.bigdawg.query.ConnectionInfo;

public class QueryContainerForCommonDatabase {
	
	private String								dbid;
	private ConnectionInfo 						databaseConnectionInfo;
	private Operator							rootOperator;
	private String								pruneToken;
	
//	public QueryContainerForCommonDatabase (Map<String, ConnectionInfo> cis, Operator o, String into) throws Exception {
	public QueryContainerForCommonDatabase (ConnectionInfo ci, String dbid, Operator o, String into) throws Exception {
		
		this.dbid 						= dbid;
		this.rootOperator				= o;
		this.pruneToken					= into;
		this.databaseConnectionInfo		= ci;
		
//		this.databaseConnectionInfos.putAll(cis);
		
	}

//	public Set<String> getConnectionStrings() throws Exception {
//		return databaseConnectionInfos.keySet();
//	}
//	
//	public Map<String, ConnectionInfo> getConnectionInfos() {
//		return databaseConnectionInfos;
//	}
	
	public String getDBID() throws Exception {
		return dbid;
	}
	
	public ConnectionInfo getConnectionInfo() {
		return databaseConnectionInfo;
	}
	
	public String generateSQLSelectIntoString() throws Exception {
		SQLQueryGenerator gen = new SQLQueryGenerator();
		gen.configure(null, true, false, null);
		rootOperator.accept(gen);
		return gen.generateSelectIntoStringForExecutionTree(pruneToken);
	}
	
	public String generateAFLStoreString() throws Exception {
		return rootOperator.generateAFLStoreStringForExecutionTree(pruneToken);
	}
	
	public String generateTreeExpression() throws Exception {
		return rootOperator.getTreeRepresentation(true);
	}
	
	public String getName() {
		return pruneToken;
	}
	
	public Map<String, Set<String>> generateObjectToExpressionMapping() throws Exception {
		Map<String, Set<String>> mapping = rootOperator.getObjectToExpressionMappingForSignature();
		rootOperator.removeCTEEntriesFromObjectToExpressionMapping(mapping);
		return mapping;
	}
}
