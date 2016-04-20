package istc.bigdawg.plan.operators;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import istc.bigdawg.extract.logical.SQLTableExpression;
import istc.bigdawg.packages.SciDBArray;
import istc.bigdawg.plan.extract.SQLOutItem;
import istc.bigdawg.plan.generators.OperatorVisitor;
import istc.bigdawg.schema.DataObjectAttribute;
import istc.bigdawg.schema.SQLAttribute;
import istc.bigdawg.utils.sqlutil.SQLExpressionUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.OrderByElement;

public class Sort extends Operator {

	
	private List<String> sortKeys;
	
	public enum SortOrder {ASC, DESC}; // ascending or descending?
	
	private SortOrder sortOrder;
	
	private List<OrderByElement> orderByElements;
//	private List<String> sortOrderStrings;
	
	private boolean isWinAgg = false; // is it part of a windowed aggregate or an ORDER BY clause?
	
	public Sort(Map<String, String> parameters, List<String> output,  List<String> keys, Operator child, SQLTableExpression supplement) throws Exception  {
		super(parameters, output, child, supplement);

		isBlocking = true;
		blockerCount++;
		this.blockerID = blockerCount;

		// two order bys might exist in a supplement:
		// 1) within an OVER () clause for windowed aggregate
		// 2) as an ORDER BY clause
		// instantiate iterator to get the right one
		// iterate from first OVER --> ORDER BY
		
		sortOrder = supplement.getSortOrder(keys, parameters.get("sectionName"));
		sortKeys = keys;

		if (children.get(0) instanceof Join) {
			outSchema = new LinkedHashMap<>();
			for(int i = 0; i < output.size(); ++i) {
				String expr = output.get(i);
				SQLOutItem out = new SQLOutItem(expr, child.outSchema, supplement); // TODO CHECK THIS TODO
				SQLAttribute attr = out.getAttribute();
				String attrName = attr.getName();
				outSchema.put(attrName, attr);
			}
		} else {
			outSchema = new LinkedHashMap<>(child.outSchema);
		}
		
		// match with previous schema to get any aliases to propagate
		for(int i = 0; i < sortKeys.size(); ++i) {
			String a = supplement.getAlias(sortKeys.get(i));
			if(a != null) 
				sortKeys.set(i, a);
		}
		
		orderByElements = new ArrayList<>();
		
		// pick out the outitems that are not columns
		Map<String, String> outExps = new HashMap<>();
		for (String s : outSchema.keySet()) {
			if (!s.equals(outSchema.get(s).getExpressionString()))
				outExps.put(s, outSchema.get(s).getExpressionString());
		}
		
		for (String s : sortKeys) {
			Expression e = CCJSqlParserUtil.parseExpression(SQLExpressionUtils.removeExpressionDataTypeArtifactAndConvertLike(s));
			SQLExpressionUtils.removeExcessiveParentheses(e);
			while (e instanceof Parenthesis) e = ((Parenthesis) e).getExpression();
			
			String estr = e.toString();
			
			estr = rewriteComplextOutItem(estr);
			
			OrderByElement obe = new OrderByElement();
			obe.setExpression(CCJSqlParserUtil.parseExpression(estr));
			if (s.endsWith("DESC")) {
				obe.setAscDescPresent(true);
				obe.setAsc(false);
			} else if (s.endsWith("ASC")) {
				obe.setAscDescPresent(true);
				obe.setAsc(true);
			}
			orderByElements.add(obe);
		}
	}
	
	// for AFL
	public Sort(Map<String, String> parameters, SciDBArray output,  List<String> keys, Operator child) throws Exception  {
		super(parameters, output, child);

		isBlocking = true;
		blockerCount++;
		this.blockerID = blockerCount;

		// two order bys might exist in a supplement:
		// 1) within an OVER () clause for windowed aggregate
		// 2) as an ORDER BY clause
		// instantiate iterator to get the right one
		// iterate from first OVER --> ORDER BY

		sortKeys = keys;
		
		outSchema = new LinkedHashMap<String, DataObjectAttribute>(child.outSchema);
		
	}
	
	public Sort(Operator o, boolean addChild) throws Exception {
		super(o, addChild);
		Sort s = (Sort) o;
		
		this.blockerID = s.blockerID;

		this.sortKeys = new ArrayList<>();
		this.setWinAgg(s.isWinAgg());
		for (String str : s.sortKeys) {
			this.sortKeys.add(new String(str));
		}
		this.sortOrder = s.sortOrder; 
		this.orderByElements = new ArrayList<>();
		for (OrderByElement ob : s.orderByElements) {
			this.orderByElements.add(ob);
		}
	}
	
//	@Override
//	public Select generateSQLStringDestOnly(Select dstStatement, boolean isSubTreeRoot, boolean stopAtJoin, Set<String> allowedScans) throws Exception {
//		dstStatement = children.get(0).generateSQLStringDestOnly(dstStatement, false, stopAtJoin, allowedScans);
//
//		if (children.get(0) instanceof Join) {
//			PlainSelect ps = (PlainSelect) dstStatement.getSelectBody();
//			ps.getSelectItems().clear();
//			for (String alias: outSchema.keySet()) {
//				Expression e = CCJSqlParserUtil.parseExpression(outSchema.get(alias).getExpressionString());
//				SelectItem s = new SelectExpressionItem(e);
//				if (!(e instanceof Column)) {
//					((SelectExpressionItem)s).setAlias(new Alias(alias));
//				}
//				ps.addSelectItems(s);
//			}
//		}
//		
//		if(!isWinAgg()) {
//			((PlainSelect) dstStatement.getSelectBody()).setOrderByElements(updateOrderByElements());
//		}
//
//		return dstStatement;
//
//	}
	
	
	public String toString() {
		return "Sort operator on columns " + sortKeys.toString() + " with ordering " + sortOrder;
	}
	
	/**
	 * PRESERVED
	 * @return
	 * @throws Exception
	 */
	public List<OrderByElement> updateOrderByElements() throws Exception {
		
		List<OrderByElement> ret = new ArrayList<>();
		
		List<Operator> treeWalker;
		for (OrderByElement obe : orderByElements) {
			
			treeWalker = children;
			boolean found = false;
			
			while (treeWalker.size() > 0 && (!found)) {
				List<Operator> nextGeneration = new ArrayList<>();
				
				for (Operator o : treeWalker) {
					if (o.isPruned()) {
						
						Column c = (Column)obe.getExpression();
						
						if (o.getOutSchema().containsKey(c.getFullyQualifiedName())) {
							OrderByElement newobe = new OrderByElement();
							newobe.setExpression(new Column(new Table(o.getPruneToken()), c.getColumnName()));
							ret.add(newobe);
							found = true;
							break;
						}
					} else {
						nextGeneration.addAll(o.children);
					}
				}
				
				treeWalker = nextGeneration;
			}
			
			
		}
		
		return ret;
	}
	
	@Override
	public void accept(OperatorVisitor operatorVisitor) throws Exception {
		operatorVisitor.visit(this);
	}
	
	@Override
	public String generateAFLString(int recursionLevel) throws Exception{
		StringBuilder sb = new StringBuilder();
		sb.append("sort(");
		sb.append(children.get(0).generateAFLString(recursionLevel+1));
		if (!sortKeys.isEmpty()) {

			updateOrderByElements();
			
			for (OrderByElement obe: orderByElements) {
				sb.append(", ").append(obe.toString());
			}
			
		}
		sb.append(')');
		return sb.toString();
		
	}
	
	@Override
	public String getTreeRepresentation(boolean isRoot) throws Exception{
		return "{sort"+children.get(0).getTreeRepresentation(false)+"}";
	}

	public boolean isWinAgg() {
		return isWinAgg;
	}

	public void setWinAgg(boolean isWinAgg) {
		this.isWinAgg = isWinAgg;
	}
	
};