package uk.ac.soton.ldanalytics.sparql2sql.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;
import com.hp.hpl.jena.sparql.algebra.OpVisitor;
import com.hp.hpl.jena.sparql.algebra.op.OpAssign;
import com.hp.hpl.jena.sparql.algebra.op.OpBGP;
import com.hp.hpl.jena.sparql.algebra.op.OpConditional;
import com.hp.hpl.jena.sparql.algebra.op.OpDatasetNames;
import com.hp.hpl.jena.sparql.algebra.op.OpDiff;
import com.hp.hpl.jena.sparql.algebra.op.OpDisjunction;
import com.hp.hpl.jena.sparql.algebra.op.OpDistinct;
import com.hp.hpl.jena.sparql.algebra.op.OpExt;
import com.hp.hpl.jena.sparql.algebra.op.OpExtend;
import com.hp.hpl.jena.sparql.algebra.op.OpFilter;
import com.hp.hpl.jena.sparql.algebra.op.OpGraph;
import com.hp.hpl.jena.sparql.algebra.op.OpGroup;
import com.hp.hpl.jena.sparql.algebra.op.OpJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpLabel;
import com.hp.hpl.jena.sparql.algebra.op.OpLeftJoin;
import com.hp.hpl.jena.sparql.algebra.op.OpList;
import com.hp.hpl.jena.sparql.algebra.op.OpMinus;
import com.hp.hpl.jena.sparql.algebra.op.OpNull;
import com.hp.hpl.jena.sparql.algebra.op.OpOrder;
import com.hp.hpl.jena.sparql.algebra.op.OpPath;
import com.hp.hpl.jena.sparql.algebra.op.OpProcedure;
import com.hp.hpl.jena.sparql.algebra.op.OpProject;
import com.hp.hpl.jena.sparql.algebra.op.OpPropFunc;
import com.hp.hpl.jena.sparql.algebra.op.OpQuad;
import com.hp.hpl.jena.sparql.algebra.op.OpQuadBlock;
import com.hp.hpl.jena.sparql.algebra.op.OpQuadPattern;
import com.hp.hpl.jena.sparql.algebra.op.OpReduced;
import com.hp.hpl.jena.sparql.algebra.op.OpSequence;
import com.hp.hpl.jena.sparql.algebra.op.OpService;
import com.hp.hpl.jena.sparql.algebra.op.OpSlice;
import com.hp.hpl.jena.sparql.algebra.op.OpTable;
import com.hp.hpl.jena.sparql.algebra.op.OpTopN;
import com.hp.hpl.jena.sparql.algebra.op.OpTriple;
import com.hp.hpl.jena.sparql.algebra.op.OpUnion;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.core.VarExprList;
import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.expr.ExprAggregator;
import com.hp.hpl.jena.sparql.expr.ExprWalker;

public class SparqlOpVisitor implements OpVisitor {
	
	RdfTableMapping mapping = null;
	List<Node> eliminated = null;
	List<Resource> traversed = new ArrayList<Resource>();
	List<Resource> blacklist = new ArrayList<Resource>();
	List<SelectedNode> selectedNodes = new ArrayList<SelectedNode>();
	Map<String,String> varMapping = new HashMap<String,String>();
	Map<String,String> aliases = new HashMap<String,String>();
	Set<String> tableList = new HashSet<String>();
	
	String selectClause = "SELECT ";
	String fromClause = "FROM ";
	String whereClause = "WHERE ";
	String groupClause = "GROUP BY ";	
	
	public SparqlOpVisitor() {
		eliminated = new ArrayList<Node>();
	}
	
	public void useMapping(RdfTableMapping mapping) {
		this.mapping = mapping;
	}

	public void visit(OpBGP bgp) {
		//result is a list of table and its columns to select and the variables they are tied to
		for(Model model:mapping.getMapping()) {
			List<Triple> patterns = bgp.getPattern().getList();
			for(Triple t:patterns) {
				Node subject = t.getSubject(); 
				if(!eliminated.contains(subject)) { //check if subject has been eliminated 
					Node predicate = t.getPredicate();
					Node object = t.getObject();
					StmtIterator stmts = getStatements(subject,predicate,object,model);
//					System.out.println("pattern:"+t);
					while(stmts.hasNext()) {
						Statement stmt = stmts.next();
						checkSubject(t,patterns,model,stmt);
//						System.out.println(stmt);
						//add statements if not eliminated
						if(!blacklist.contains(stmt.getSubject())) {
							SelectedNode node = new SelectedNode();
							node.setStatement(stmt);
							node.setBinding(t);
							selectedNodes.add(node);
						}
					}
				}
			}
			
//			System.out.println("-----------\n\n"+selectedNodes.size());
			for(SelectedNode n:selectedNodes) {
				if(n.isLeafValue()) {
					String modifier = "";
					if(!whereClause.equals("WHERE ")) {
						modifier = " AND ";
					}
					whereClause += modifier + n.getWherePart();
				} else if(n.isLeafMap()) {
//					System.out.println(n.getVar() + ":" + n.getTable() + "." + n.getColumn());
					varMapping.put(n.getVar(), n.getColumn());
					tableList.add(n.getTable());
				}
			}
		}
	}

	private StmtIterator getStatements(Node subject, Node predicate, Node object, Model model) {
		Resource s = subject.isVariable() ? null : model.asRDFNode(subject).asResource();
		Property p = predicate.isVariable() ? null : model.createProperty(predicate.getURI());
		RDFNode o = null;
		if(object.isLiteral()) {
			o = null;
		} else if(object.isVariable()) {
			o = null;
		} else {
			o = model.asRDFNode(object);
		}
		return model.listStatements(s, p, o);
	}

	private void checkSubject(Triple originalTriple, List<Triple> patterns, Model model, Statement stmt) {
		for(Triple t:patterns) {
			if(!t.matches(originalTriple)) {
				if(t.subjectMatches(originalTriple.getSubject())) {
					Node subject = stmt.getSubject().asNode();
					Node predicate = t.getPredicate();
					Node object = t.getObject();
					StmtIterator stmts = getStatements(subject,predicate,object,model);
					if(!stmts.hasNext()) {
						//eliminate
						eliminate(t,stmt, model, patterns);
//						System.out.println("no statement:"+t);
						eliminated.add(t.getSubject());
						break;
					}
//					System.out.println("matches statement:"+t);
				}
			}
		}
	}

	private void eliminate(Triple t, Statement stmt, Model model, List<Triple> patterns) {
		//check if its a branch and where the branch is
		Node subject = t.getSubject();
		Node predicate = t.getPredicate();
		Node object = t.getObject();
		StmtIterator stmts = getStatements(subject,predicate,object,model);
		List<Resource> validSubjects = new ArrayList<Resource>();
		while(stmts.hasNext()) {
			Statement correctStmt = stmts.next();
			validSubjects.add(correctStmt.getSubject());
		}
//		Boolean hasBranch = validSubjects.size() > 0;
//		if(hasBranch) { //calculate the common node where it branches
//			calculateBranchNode(stmt.getSubject(),validSubjects,model);
//		}
			
		//backward elimination recursion and mark forward elimination/blacklisting
		eliminateR(stmt.getSubject(),validSubjects,model, 0, stmt.getSubject(), patterns, t.getSubject(), null);
		
		//forward elimination
		//add subject to list and also all connected objects if not branch
	}
	
	private int eliminateR(Resource subject, List<Resource> validSubjects,
			Model model, int count, Resource parent, List<Triple> patterns, Node var, List<Resource> path) {
//		System.out.println(parent+"->"+subject);
		traversed.add(subject);
		
		if(validSubjects.contains(subject)) {
			return count;
		}
		
		for(Triple t:patterns) {
			StmtIterator stmts = null;
			Node nextVar = null;
			if(t.subjectMatches(var)) {
				stmts = model.listStatements(subject,null,(RDFNode)null);
				nextVar = t.getObject();
			}
			else if(t.objectMatches(var)) {
				stmts = model.listStatements(null,null,subject);
				nextVar = t.getMatchSubject();
			}
			
			if(stmts!=null) {
				while(stmts.hasNext()) {
					if(path==null) { //create new path for each branch out
						path = new ArrayList<Resource>();
					}
					
					Statement stmt = stmts.next();
					Resource nextNode = null;
					if(t.subjectMatches(var)) {
						if(!stmt.getObject().isLiteral()) {
							nextNode = stmt.getObject().asResource();
						} else {
							continue;
						}
					} else if(t.objectMatches(var)) {
						nextNode = stmt.getSubject();
					}
		//			System.out.println(o.isLiteral() + ":" + o + ":" + subject + ":" + stmt.getPredicate());
					if(!traversed.contains(nextNode)) {
		//				System.out.println(o);
						int tempResult = eliminateR(nextNode.asResource(),validSubjects,model,count++,subject,patterns,nextVar,path);
						//on the wind down add the branch
						path.add(nextNode);
						if(tempResult>0) {
							if(count==(tempResult+1)/2) {
								//clear the path down the line
								path.clear();
//								System.out.println("\n\n"+nextNode);
							} else {
								return tempResult;
							}
						}
					}
				}
			}
		}
		
		if(subject.equals(parent)) { //reached the top, eliminate all on this path
			path.add(subject);
			eliminateNodes(path);
		}
		
		return 0;
	}
	
	private void eliminateNodes(List<Resource> path) {		
		//eliminate nodes in list	
		Iterator<SelectedNode> i = selectedNodes.iterator();
		while (i.hasNext()) {
		   SelectedNode node = i.next(); // must be called before you can call i.remove()
		   if(path.contains(node.getSubject())) {
			   i.remove();
			}
		}
		
		//blaclist/forward elimination
		blacklist.addAll(path);
	}

//	private Resource calculateBranchNode(Resource invalidNode, List<Resource> validSubjects, Model model) {
//		
//		//trivial case: they are connected to each other directly
//		StmtIterator stmts = model.listStatements(invalidNode,null,(RDFNode)null);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getObject().asResource())) {
//				validSubjects.remove(stmt.getObject().asResource());
//			}
//		}
//		stmts = model.listStatements(null,null,invalidNode);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getSubject())) {
//				validSubjects.remove(stmt.getSubject());
//			}
//		}
//		if(validSubjects.isEmpty()) {
//			return invalidNode;
//		}
//		
//		//expand by one neighbour each time to find common node
//		stmts = model.listStatements(invalidNode,null,(RDFNode)null);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getObject().asResource())) {
//				validSubjects.remove(stmt.getObject().asResource());
//			}
//		}
//		stmts = model.listStatements(null,null,invalidNode);
//		while(stmts.hasNext()) {
//			Statement stmt = stmts.next();
//			if(validSubjects.contains(stmt.getSubject())) {
//				validSubjects.remove(stmt.getSubject());
//			}
//		}
//		
//		return invalidNode;
//		
//	}

	public void visit(OpQuadPattern arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpQuadBlock arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpTriple arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpQuad arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpPath arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpTable arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpNull arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpProcedure arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpPropFunc arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpFilter filters) {
//		System.out.println("Filter");		
		
		for(Expr filter:filters.getExprs().getList()) {
			SparqlFilterExprVisitor v = new SparqlFilterExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v,filter);
			v.finishVisit();
			String modifier = "";
			if(!whereClause.equals("WHERE ")) {
				modifier = " AND ";
			}
			whereClause += modifier + v.getExpression(); 
		}
		
//		System.out.println(whereClause);
	}

	public void visit(OpGraph arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpService arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDatasetNames arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpLabel arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpAssign arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpExtend arg0) {
//		System.out.println("extend");
		VarExprList vars = arg0.getVarExprList();
		for(Var var:vars.getVars()) {
			String originalKey = vars.getExpr(var).getVarName();
			if(aliases.containsKey(originalKey)) {
				String val = aliases.remove(originalKey);
				aliases.put(var.getName(), val);
			}
		}
	}

	public void visit(OpJoin arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpLeftJoin arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpUnion arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDiff arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpMinus arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpConditional arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpSequence arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDisjunction arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpExt arg0) {
		
		
	}

	public void visit(OpList arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpOrder arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpProject arg0) {
//		System.out.println("project");
		int count=0;
		for(Var var:arg0.getVars()) {
			if(count++>0) {
				selectClause += " , ";
			}
			if(aliases.containsKey(var.getName())) {
				selectClause += aliases.get(var.getName()) + " AS " + var.getName();
			} else if(varMapping.containsKey(var.getName())){
				String rdmsName = varMapping.get(var.getName());
				selectClause += rdmsName;
				if(!rdmsName.equals(var.getName())) {
					selectClause += " AS " + var.getName();
				}
			} else {
				count--;
			}
		}
//		System.out.println(selectClause);
	}

	public void visit(OpReduced arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpDistinct arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpSlice arg0) {
		// TODO Auto-generated method stub
		
	}

	public void visit(OpGroup group) {
//		System.out.println("group");
		VarExprList vars = group.getGroupVars();
		Map<Var,Expr> exprMap = vars.getExprs();
		int count = 0;
		for(Var var:vars.getVars()) {
			Expr expr = exprMap.get(var);
			SparqlGroupExprVisitor v = new SparqlGroupExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v, expr);
			if(count++>0) {
				groupClause += " , ";
			}
			if(!v.getExpression().equals("")) {
				groupClause += v.getExpression();
				aliases.put(var.getName(), v.getExpression());
			} else {
				groupClause += var.getName();
			} 
		}
//		System.out.println(groupClause);
		for(ExprAggregator agg:group.getAggregators()) {
			SparqlGroupExprVisitor v = new SparqlGroupExprVisitor();
			v.setMapping(varMapping);
			ExprWalker.walk(v, agg);
			aliases.put(v.getAggKey(),v.getAggVal());
		}
	}

	public void visit(OpTopN arg0) {
		
		
	}
	
	public String getSQL() {
		int count = 0;
		for(String table:tableList) {
			if(count++>0) {
				fromClause += " , ";
			}
			fromClause += table;
		}
		
		if(selectClause.equals("SELECT ")) {
			return "";
		} else if(fromClause.equals("FROM ")) {
			return "";
		} else if(whereClause.equals("WHERE ")) {
			whereClause = "";
		} else if(groupClause.equals("GROUP BY ")) {
			groupClause = "";
		}
		
		return selectClause + " " +
				fromClause + " " +
				whereClause + " " +
				groupClause + " ";
	}

}
