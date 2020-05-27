package hr.pmf.nbp.projekt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;

/**
 * Class offers a method that finds the fastest path, that is - the shortest path in the
 * meaning of time needed to get from starting point to destination point.
 * The implemented algorithm is a simple version of Dijkstra's shortest path algorithm.
 * It returns result tuples (cityName, timeToGetToTheCity) of each node in the path.
 *
 * @author team Theta
 *
 */
public class FastestPath {

    @Context
    public GraphDatabaseService db;
    
    @Context
    public Transaction transaction;

    @Context
    public Log log;
    
    //contains all unchecked nodes
    private static Set<Node> Q;
    
    //contains tuples (city, minimumTimeToGetThere)
    private static Map<Node,Duration> timeTo;
    
    //contains tuples (city, cityThatPrecedesIt)
    private static Map<Node,Node> visited;
        
    /**
     * Finds the fastest path from one city to another, and returns the checkpoints along the way.
     * If the panorama flag is set to true, the path is understood to go through all cities
     * along the way. Otherwise, the cities on a highway are 'skipped'.
     * The node representing cities has following properties: 'name' and 'duration'(time needed to get across it).
     * The connection between cities has following properties: 'duration' and 'roadType'.
     * @param startCity		The name of the starting city.
     * @param endCity		The name of the target city.
     * @param panorama		A flag showing if all cities on the path are visited directly.
     * @return				Tuples of (cityName, timeToGetToTheCity) of each city in the path.
     */
    @Procedure( value = "fastestPath" )
    @Description( "Finds the fastest path from one city to another, and returns the checkpoints along the way." )
    public Stream<Checkpoint> fastestPath( @Name( "starting point" ) String startCity, 
    		@Name( "destination" )String endCity, @Name( "panorama" )Boolean panorama ) {
    	
    	initialise();
    	
    	Node start = findNodeByName( startCity );
    	Node destination = findNodeByName( endCity );
    	
    	if ( start == null || destination == null ) {
    		throw new RuntimeException( "Invalid name of starting or destination city." );
    	}
    	
    	timeTo.put( start, Duration.ZERO );
    	
    	while( !Q.isEmpty() ) {
    		
    		Node closest = findClosestInQ();
    		
    		Q.remove( closest );
    		
    		Map<Node,Relationship> neighbours = getNeighbours( closest );
    		
    		for( Map.Entry<Node,Relationship> element : neighbours.entrySet() ) {
    			
    			Node neighbour = element.getKey();
    			Relationship neighbourRelation = element.getValue();
    			
    			Relationship previousRelation = getRelationship( closest, visited.get( closest ) );
    			
    			Duration newTimeTo = timeTo.get( closest ).plus( Duration.parse( neighbourRelation.getProperty( "duration" ).toString() ) );
    			
    			//check if the city has to be directly visited
    			if( panorama || !neighbourRelation.getProperty( "roadType" ).equals( "highway" ) 
    					|| previousRelation == null || !previousRelation.getProperty( "roadType" ).equals( "highway" ) ) {
    				
    				newTimeTo = newTimeTo.plus( Duration.parse( closest.getProperty( "tourDuration" ).toString() ) );
    			}
    			if ( newTimeTo.compareTo( timeTo.get( neighbour ) ) < 0 ) {
    				
    				timeTo.put( neighbour, newTimeTo );
    				visited.put( neighbour, closest );
    			}
    		}
    	}
    	
    	List<Node> path = new ArrayList<>();
    	
    	Node current = destination;
    	while( timeTo.get( current ) != Duration.ZERO ) {
    		
    		path.add( current );
    		current = visited.get( current );
    	}
    	path.add( start );
    	
    	Collections.reverse( path );
    	
    	return path.stream().map( node -> new Checkpoint( node, timeTo.get( node ) ) );
    }
    
    /**
     * Utility method.
     * Initialises the containers to appropriate values according to Dijkstra's algorithm.
     */
    private void initialise() {
    	
    	Q = new HashSet<>();
    	transaction.getAllNodes().forEach( node -> Q.add( node ) );
    	
    	timeTo = new HashMap<>();
    	Q.forEach( node -> timeTo.put( node, ChronoUnit.FOREVER.getDuration() ) );
    	
    	visited = new HashMap<>();
    	Q.forEach( node -> visited.put( node, null ) );
    	
    }
    
    /**
     * Utility method.
     * Finds the city that takes the least time to reach (at current point in
     * the algorithm) and is yet unchecked.
     * @return		The node most easily reached from the starting point.
     */
    private Node findClosestInQ() {
    	
    	Node closest = null;
    	Duration minDuration = ChronoUnit.FOREVER.getDuration();
    	
    	for( Node node : Q ) {
    		
    		if ( minDuration.compareTo( timeTo.get( node ) ) > 0 ) {
    			minDuration = timeTo.get( node );
    			closest = node;
    		}
    	}
    	return closest;
    }
    
    /**
     * Utility method.
     * Gets the nodes directly connected to the node passed as an argument.
     * @param node		Node of interest.
     * @return			Map of tuples (node, relationshipThatConnectsThem).
     */
    private Map<Node,Relationship> getNeighbours( Node node ) {
    	
    	Map<Node,Relationship> neighbours = new HashMap<>();
    	for ( Relationship relation : node.getRelationships() ) {
    		
    		Node neighbour = relation.getOtherNode( node );
    				
    		neighbours.put( neighbour, relation );
    	}
    	return neighbours;
    	
    }
    
    /**
     * Utility method.
     * Gets the relationship between the passed nodes.
     * @param first		Start node.
     * @param second	End node.
     * @return			Relationship between the nodes, or null if there is none.
     */
    private Relationship getRelationship( Node first, Node second ) {
    	
    	if ( first == null || second == null ) {
    		return null;
    	}
    	
    	for ( Relationship relation : first.getRelationships() ) {
    		
    		if ( relation.getOtherNodeId( first.getId() ) == second.getId() ) {
    			return relation;
    		}
    	}
    	return null;
    }
    
    /**
     * Utility method.
     * Finds node by its name.
     * @param name		The name of the city.
     * @return			The node with the name property as passed in argument.
     */
    private Node findNodeByName( String name ) {
    	
    	for ( Node node : transaction.getAllNodes() ) {
    		
    		if ( node.getProperty( "name" ).equals( name ) ) {
    			return node;
    		}
    	}
    	return null;
    }
    
    /**
     * The class whose instances the result stream is built of.
     * An instance represents the checkpoint in the path - containing
     * the name of the city as well as the amount of time needed to get there.
     */
    public static class Checkpoint {
    	
    	public String name;
    	public TemporalAmount cost;
    	
    	public Checkpoint( Node node, TemporalAmount cost ) {
    		
    		name = (String) node.getProperty( "name" );
    		this.cost = cost;
    	}
    }
}
