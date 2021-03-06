package com.aaronpmaus.jProt.metrics;

import com.aaronpmaus.jProt.protein.*;
import com.aaronpmaus.jProt.tools.*;
import com.aaronpmaus.jProt.sequence.*;

import com.aaronpmaus.jMath.graph.*;
import com.aaronpmaus.jMath.linearAlgebra.*;

import java.util.Date;
import java.util.Scanner;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * <p>Metrics is a collection of protein similarity metrics. It consists of Angular Distance,
 * Local Similarity, and Global Similarity.</p>
 *
 * <p>To calculate Angular Distance, we vectorize the Carbon Alpha Distance matrices of two proteins
 * and calculate the angle between those two vectors.</p>
 *
 * <p>Local similarity takes the difference of the two distance matrices, builds a graph out of the
 * differences, finds the max clique (that is, max region where all residues are the same distance
 * apart in both structures) of that graph, removes all those nodes from the graph, finds the next
 * max clique and continues to find a clique covering of the graph. That is, a set of regions that
 * are all internally consistent and cover the protein structure. To build the graph, if two
 * residues are the same distance apart in the two structures, we draw an edge between vertices
 * representing those residues. In practice, for the two residues, if the difference of their
 * distance from the two distance matrices is below a threshold, we say they are the same and draw
 * an edge.</p>
 *
 * <p>Global similarity relies on the same ideas for building a graph from the differences matrix
 * and finding regions that are internally consistent, but it operates as follows. It uses a series
 * of thresholds. eg. 1.0, 2.0, 4.0, 8.0. It builds a graph using each threshold and finds the max
 * clique for each graph. We get a set of regions that are subsequently larger (since we are
 * relaxing out idea of "the same"). Taking the average of the percent of residues under each
 * threshold gives us a score (credit to Zemla for the recipe for this score).</p>
 *
 * @version 0.5.0
 * @since 0.5.0
*/
public class Metrics{
  private Double[][] differencesMatrix;
  private Double[][] alphaDistancesMatrix;
  private Double[][] betaDistancesMatrix;
  private String[] alphaResidueIDs;
  private String[] betaResidueIDs;
  private String alphaStrucID;
  private String betaStrucID;
  private int numResiduesInReference;

  /**
  * A constructor that both the distance matrix files. It will calculate the values for the differences.
  * @param alphaDistancesFileName the file containing the distances matrix for the first structure
  * @param betaDistancesFileName the file containing the distances matrix for the second structure
  * @throws FileNotFoundException if any of the files are not found
  * @since 0.5.0
  */
  public Metrics(String alphaDistancesFileName, String betaDistancesFileName) throws FileNotFoundException{
    // read in the residue IDs for both structures
    Scanner reader = new Scanner(new File(alphaDistancesFileName));
    this.alphaResidueIDs = reader.nextLine().split(",");
    this.numResiduesInReference = this.alphaResidueIDs.length;
    reader = new Scanner(new File(betaDistancesFileName));
    this.betaResidueIDs = reader.nextLine().split(",");

    // get the structure name from each file. Will need this name for building
    // the pymol selection commands later.
    // This requires that the distance matrix file begin with the PDB id.
    // something like PDBid.pdb.CADistanceMatrix.csv is a good idea.
    String[] fileNameTokens = alphaDistancesFileName.split("/"); // all but last element is path.
    String fileName = fileNameTokens[fileNameTokens.length-1]; // get the last element
    this.alphaStrucID = fileName.split("\\.")[0]; // split the filename on . and take the zeroth.
    fileNameTokens = betaDistancesFileName.split("/"); // all but last element is path.
    fileName = fileNameTokens[fileNameTokens.length-1]; // get the last element
    this.betaStrucID = fileName.split("\\.")[0];

    // read in the distance matrices and calculate the differences
    this.alphaDistancesMatrix = readInDistanceFile(alphaDistancesFileName);
    this.betaDistancesMatrix = readInDistanceFile(betaDistancesFileName);
    calculateDifferencesMatrix();
  }

  /**
  * Build Metrics object taking in the two proteins to compare.
  *
  * It will first perform a sequence alignment and then build the carbon alpha distance
  * matrices out of the residues from that alignment that were matched.
  *
  * @param reference the structure to serve as the base of comparison
  * @param structure the the structure to compare against the reference
  */
  public Metrics(Protein reference, Protein structure){
    ProteinSequence prot1Sequence = reference.getSequence();
    ProteinSequence prot2Sequence = structure.getSequence();
    this.numResiduesInReference = reference.getNumResidues();
    // first we need an alignment of the sequences of these proteins
    Alignment alignment = prot1Sequence.align(prot2Sequence);
    // get masks indicating which residues in each protein have a match in the other protein.
    boolean[] prot1Mask = alignment.getAlignmentMask(prot1Sequence);
    boolean[] prot2Mask = alignment.getAlignmentMask(prot2Sequence);

    // get the residue ids from prot1 that were aligned with residues in prot2
    Integer[] protOneResIDs = reference.getResidueIDs(prot1Mask);
    this.alphaResidueIDs = new String[protOneResIDs.length];
    int i = 0;
    for(Integer id : protOneResIDs){
      this.alphaResidueIDs[i] = id.toString();
      i++;
    }

    // get the residue ids from prot2 that were aligned with residues in prot1
    Integer[] protTwoResIDs = structure.getResidueIDs(prot2Mask);
    this.betaResidueIDs = new String[protTwoResIDs.length];
    i = 0;
    for(Integer id : protTwoResIDs){
      this.betaResidueIDs[i] = id.toString();
      i++;
    }

    this.alphaStrucID = reference.getProteinName();
    this.betaStrucID = structure.getProteinName();
    // build the distance matrices out of the residues that were aligned
    //this.alphaDistancesMatrix = prot1.calculateCarbonAlphaDistanceMatrix(prot1Mask);
    //this.betaDistancesMatrix = prot2.calculateCarbonAlphaDistanceMatrix(prot2Mask);
    this.alphaDistancesMatrix = DistanceMatrixCalculator.calculateDistanceMatrix(reference, prot1Mask);
    this.betaDistancesMatrix = DistanceMatrixCalculator.calculateDistanceMatrix(structure, prot2Mask);
    calculateDifferencesMatrix();
  }

  /**
  * A private helper method to calculate the differences matrix from the
  * two distance matrices
  */
  private void calculateDifferencesMatrix(){
    this.differencesMatrix = new Double[getNumResidues()][getNumResidues()];
    for(int i = 0; i < this.alphaDistancesMatrix.length; i++){
      for(int j = 0; j < this.alphaDistancesMatrix[i].length; j++){
        this.differencesMatrix[i][j] = Double.NaN;
      }
    }
    // only use the values from the upper right hand side of the matrix.
    for(int i = 0; i < this.alphaDistancesMatrix.length; i++){
      for(int j = i+1; j < this.alphaDistancesMatrix[i].length; j++){
        this.differencesMatrix[i][j] = Math.abs(this.alphaDistancesMatrix[i][j] - this.betaDistancesMatrix[i][j]);
      }
    }
  }

  /*
  * a private helper method that takes in a distances file and returns a 2D array with the
  * values.
  * @param fileName the file to read the distances from.
  */
  private Double[][] readInDistanceFile(String fileName) throws FileNotFoundException{
    Scanner fileReader = new Scanner(new File(fileName));
    fileReader.nextLine(); // throw away the first line. it's the residue IDs.

    ArrayList<String> dataLines = new ArrayList<String>();
    while(fileReader.hasNext()){
      dataLines.add(fileReader.nextLine());
    }
    int numResidues = dataLines.size();
    Double[][] data = new Double[numResidues][numResidues];
    for(int i = 0; i < numResidues; i++){
      for(int j = 0; j < numResidues; j++){
        data[i][j] = Double.NaN;
      }
    }
    // only use the values from the upper right hand side of the matrix.
    for(int i = 0; i < dataLines.size(); i++){
      String[] tokens = dataLines.get(i).split(",");
      for(int j = i+1; j < tokens.length; j++){
        data[i][j] = Double.parseDouble(tokens[j]);
      }
    }
    return data;
  }

  /**
  * A query to get the differences matrix
  * @return a 2D array of Doubles containing the differences values
  * @since 0.5.0
  */
  public Double[][] getDifferencesMatrix(){
    return this.differencesMatrix;
  }

  /**
  * A query to get the alpha distance matrix
  * @return a 2D array of Doubles containing the values
  * @since 0.5.0
  */
  public Double[][] getAlphaDistancesMatrix(){
    return this.alphaDistancesMatrix;
  }

  /**
  * A query to get the beta distance matrix
  * @return a 2D array of Doubles containing the values
  * @since 0.5.0
  */
  public Double[][] getBetaDistancesMatrix(){
    return this.betaDistancesMatrix;
  }

  /**
  * A query to get the residue IDs of the alpha structure
  * @return a array of Strings containing the IDs
  * @since 0.5.0
  */
  public String[] getAlphaResidueIDs(){
    return this.alphaResidueIDs;
  }

  /**
  * A query to get the residue IDs of the beta structure
  * @return a array of Strings containing the IDs
  * @since 0.5.0
  */
  public String[] getBetaResidueIDs(){
    return this.betaResidueIDs;
  }

  /**
  * A query to get the number of residues in the alignment.
  * @return the number of residues
  * @since 0.5.0
  */
  public int getNumResidues(){
    return getAlphaResidueIDs().length;
  }

  // return the number of residues in the reference structure. The scores are based on the percent
  // of residues that match those in the reference.
  private int getNumResiduesInReferenceStructure(){
    return this.numResiduesInReference;
  }

  /**
  * A method to calculate the Angular Distance. Given two structure distance matrices, it
  * flattens them into a vector and calculates the angle between them. It scales the angle
  * to be in the range 0-100 where 0 is identical and returns that value.
  * @return the angular distance between the two structures. range 0-100. 0 is identical.
  * @since 0.5.0
  */
  public double angularDistance( ) throws NullPointerException{
    if(getAlphaDistancesMatrix() == null || getBetaDistancesMatrix() == null){
      throw new NullPointerException("Can not calculate angular distance. No alpha distances or beta distances available.");
    }
    Vector alphaVec = buildVector(getAlphaDistancesMatrix());
    Vector betaVec = buildVector(getBetaDistancesMatrix());
    return alphaVec.angle(betaVec)*100/90; // adjust the range to be 0-100
  }

  /*
  * A private helper method to flatten a matrix into a vector.
  */
  private Vector buildVector(Double[][] matrix){
    int numRes = getNumResidues();
    int numValues = ((numRes - 1)*numRes)/2;
    Double[] vals = new Double[numValues];
    int counter = 0;
    for(int i = 0; i < matrix.length; i++){
      for(int j = i+1; j < matrix[i].length; j++){
        vals[counter] = matrix[i][j];
        counter++;
      }
    }
    return new Vector(vals);
  }

  /**
  * Returns an ArrayList of UndirectedGraphs representing the similarity regions
  * under the default threshold of 1.0 angstroms. That is, if the difference of
  * the distances between two residues is less than 1.0 angstroms, we say they are
  * "the same" distance apart in both structures. We draw an edge between them in
  * the similarity graph of the structures and use it to find regions of internal
  * consistency.
  * @return an ArrayList of the graphs of the regions of local similarity. Each graph
  *          consists of nodes representing the residues in the region of similarity.
  * @since 0.5.0
  */
  public ArrayList<UndirectedGraph<Integer>> getLocalSimilarityRegions(){
    return getLocalSimilarityRegions(1.0);
  }

  /**
  * Returns an ArrayList of UndirectedGraphs representing the similarity regions
  * under the threshold passed in as an argument. That is, if the difference of
  * the distances between two residues is less than the threshold, we say they are
  * "the same" distance apart in both structures. We draw an edge between them in
  * the similarity graph of the structures and use it to find regions of internal
  * consistency.
  * @param threshold the threshold to use when building the similarity graph.
  * @return an ArrayList of the graphs of the regions of local similarity. Each graph
  *          consists of nodes representing the residues in the region of similarity.
  * @since 0.5.0
  */
  public ArrayList<UndirectedGraph<Integer>> getLocalSimilarityRegions(double threshold){
    UndirectedGraph<Integer> graph = buildSimilarityGraph(threshold);
    System.out.printf("\nGraph built for structures under threshold %.2f.\n",threshold);
    System.out.printf("Num Vertices: %d\n",graph.size());
    System.out.printf("Num Edges: %d\n",graph.numEdges());
    System.out.printf("Density: %.2f\n",graph.density());
    MaxCliqueSolver<Integer> maxCliqueTool = new IncMaxCliqueAdapter();
    //MaxCliqueSolver<Integer> maxCliqueTool = new MausMaxCliqueSolver();
    ArrayList<UndirectedGraph<Integer>> cliques = maxCliqueTool.getCliqueCovering(graph);
    return cliques;
  }

  /**
  * Returns an ArrayList of the graphs of the regions of similarity under the default
  * global distance thresholds of 1.0, 2.0, 4.0, and 8.0 angstroms.
  * @return an ArrayList of the graphs of the regions of similarity under the default
  *          global distance thresholds of 1.0, 2.0, 4.0, and 8.0 angstroms.
  * @since 0.5.0
  */
  public ArrayList<UndirectedGraph<Integer>> getGlobalDistanceRegions(){
    double[] thresholds = {1.0, 2.0, 4.0, 8.0};
    return getGlobalDistanceRegions(thresholds);
  }

  /**
  * Returns an ArrayList of the graphs of the regions of similarity under the
  * global distance thresholds passed in.
  * @param thresholds the thresholds to use to find the regions of similarity.
  * @return an ArrayList of the graphs of the regions of similarity under the
  *          global distance thresholds passed in.
  * @since 0.5.0
  */
  public ArrayList<UndirectedGraph<Integer>> getGlobalDistanceRegions(double[] thresholds){
    ArrayList<UndirectedGraph<Integer>> regions = new ArrayList<UndirectedGraph<Integer>>();
    UndirectedGraph<Integer> lastClique = null;
    for(int i = 0; i < thresholds.length; i++){
      double threshold = thresholds[i];
      UndirectedGraph<Integer> graph = buildSimilarityGraph(threshold);
      //System.out.printf("Num Vertices: %d\n",graph.size());
      //System.out.printf("Num Edges: %d\n",graph.numEdges());
      //System.out.printf("Density: %.2f\n",graph.density());
      String fname = graph.getGraphFileName();
      UndirectedGraph<Integer> clique;
      MaxCliqueSolver<Integer> maxCliqueTool = new IncMaxCliqueAdapter();
      //MaxCliqueSolver<Integer> maxCliqueTool = new MausMaxCliqueSolver();
      long startTime = new Date().getTime();
      if(i == 0){
        clique = maxCliqueTool.findMaxClique(graph);
      } else {
        graph = graph.getNeighborhood(lastClique.getNodes());
        graph.setGraphFileName(fname);
        clique = maxCliqueTool.findMaxClique(graph);
      }
      long endTime = new Date().getTime();
      System.out.printf("\nRegion Found for structures under threshold %.2f.\n",threshold);
      System.out.printf("%10s |%13s | %10s | %10s | %10s\n", "Threshold", "Num Vertices", "Num Edges", "Density", "Runtime");
      System.out.printf("%8.2f A | %12d | %10d | %10.2f | %10d\n", threshold, graph.size(), graph.numEdges(), graph.density(), endTime-startTime);
      regions.add(clique);
      lastClique = clique;
    }
    return regions;
  }

  /**
  * Calculates and returns the percent of residues in each region of similarity along with
  * the average of those percents.
  * @param regions an ArrayList of the graphs of the regions of similarity. Each graph
  *                  consists of nodes residues in that region of similarity.
  * @return a 2D array. The number of rows is regions.size()+1. Each row has 2 columns.
  *          The i-th row contains at index 0 the number of residues in that region and
  *          at index 1 the percent of residues in that regions.
  *          The last row contains at index 0 the average of the number of residues in
  *          each region and at index 1 the average of the percent of residues in each region.
  * @since 0.5.0
  */
  public double[][] getGlobalDistanceTestScore(ArrayList<UndirectedGraph<Integer>> regions){
    double numRes = getNumResiduesInReferenceStructure();
    int numRegions = regions.size();
    double[][] ret = new double[numRegions+1][2];
    double numResAve = 0;
    double percentAve = 0;
    for(int i = 0; i < numRegions; i++){
      ret[i][0] = regions.get(i).size();
      ret[i][1] = ret[i][0] / numRes;
      numResAve += ret[i][0];
      percentAve += ret[i][1];
    }
    ret[numRegions][0] = numResAve/(double)numRegions;
    ret[numRegions][1] = percentAve/(double)numRegions;
    //ret[0] = regions.get(0).size() / numRes;
    //ret[1] = regions.get(1).size() / numRes;
    //ret[2] = regions.get(2).size() / numRes;
    //ret[3] = regions.get(3).size() / numRes;
    //ret[4] = (ret[0] + ret[1] + ret[2] + ret[3])/4;
    return ret;
  }

  /**
  * Builds and returns an ArrayList containing the pymol commands to color a protein given
  * a set of regions of similarity.
  * @param regions an ArrayList of the graphs representing the regions of similarity.
  * @return the set of pymol commands to properly color the structure.
  * @since 0.5.0
  */
  public ArrayList<String> getPymolColoringScript(ArrayList<UndirectedGraph<Integer>> regions){
    ArrayList<String> pymolScript = new ArrayList<String>();
    int numRes = getNumResidues();
    int numRegions = regions.size();
    pymolScript.add("hide everything");
    pymolScript.add("show cartoon");
    int counter = 1;
    for(UndirectedGraph<Integer> region : regions){
      pymolScript.add("select clique"+counter+", " + alphaStrucID + " and i. "
      + getNodesString(region, "+", getAlphaResidueIDs()) + " or "
      + betaStrucID + " and i. " + getNodesString(region, "+", getBetaResidueIDs()));
      counter++;
    }
    if(regions.size() > 3){
      pymolScript.add("color red");
      pymolScript.add("color orange, clique4");
      pymolScript.add("color yellow, clique3");
      pymolScript.add("color cyan, clique2");
      pymolScript.add("color green, clique1");
    } else {
      pymolScript.add("color red");
      String[] colors = {"green", "cyan", "yellow", "orange"};
      for(int i = regions.size(); i > 0; i--){
        pymolScript.add("color " + colors[i-1] + ", clique"+i);
      }
    }
    return pymolScript;
  }

  public ArrayList<String> getChimeraColoringScript(ArrayList<UndirectedGraph<Integer>> regions){
    ArrayList<String> chimeraScript = new ArrayList<String>();
    int numRes = getNumResidues();
    int numRegions = regions.size();
    int i = 0;
    //                  Dark Blue  Light Blue  Yellow    Orange
    //String[] colors = {"#2c7bb6", "#abd9e9", "#ffffbf", "#fdae61"};
    String[] colors = {"green", "cyan", "yellow", "orange"};
    for(UndirectedGraph<Integer> region : regions){
      chimeraScript.add(String.format("color %s #0:%s; color %s #1:%s",
                      colors[i], getNodesString(region, ",", getAlphaResidueIDs()),
                      colors[i], getNodesString(region, ",", getBetaResidueIDs())));
      i++;
      if(i==3){
        break;
      }
    }
    //                      Red
    chimeraScript.add("color #d7191c");
    Collections.reverse(chimeraScript);
    return chimeraScript;
  }

  /**
  * Return the corresponding residue IDs for those residues in the first Protein in this region.
  * The first protein is that passed as the first argument to the constructor.
  * @param region one of the regions of similarity returned by either getGlobalDistanceRegions or
  * getLocalSimilarityRegions
  * @return an ArrayList containing the residue IDs of the residues in that region.
  */
  public ArrayList<Integer> getProt1ResIDsInRegion(UndirectedGraph<Integer> region){
    return getResIDsInRegion(region, getAlphaResidueIDs());
  }

  /**
  * Return the corresponding residue IDs for those residues in the second Protein in this region.
  * The second protein is that passed as the second argument to the constructor.
  * @param region one of the regions of similarity returned by either getGlobalDistanceRegions or
  * getLocalSimilarityRegions
  * @return an ArrayList containing the residue IDs of the residues in that region.
  */
  public ArrayList<Integer> getProt2ResIDsInRegion(UndirectedGraph<Integer> region){
    return getResIDsInRegion(region, getBetaResidueIDs());
  }

  private ArrayList<Integer> getResIDsInRegion(UndirectedGraph<Integer> region, String[] resIDs){
    ArrayList<Integer> ids = new ArrayList<Integer>();
    for(Node<Integer> node : region){
      ids.add(Integer.parseInt(resIDs[node.get()]));
    }
    return ids;
  }

  /*
  * a private helper method to build a similarity graph from the differences matrix given
  * a threshold.
  * @param threshold the threshold to use when determining whether to add an edge or not
  * @return the similarity graph built from that threshold. It consists of a node for each
  *          residue and an edge between two residues if the difference of their distance
  *          within each of the structures is less than the threshold.
  */
  private UndirectedGraph<Integer> buildSimilarityGraph( double threshold ){
    UndirectedGraph<Integer> graph = new UndirectedGraph<Integer>();
    Double[][] data = getDifferencesMatrix();
    Double[][] referenceDistances = getAlphaDistancesMatrix();
    //String dataString = ""; // building the String for debugging purposes is VERY slow (minutes!).
    for(int row = 0; row < data.length; row++){
      for(int col = 0; col < data[row].length; col++){
        if(data[row][col].isNaN()){
          //dataString += " N";
        } else {
          double val = data[row][col];
          double refDist = referenceDistances[row][col];
          //if(refDist < 15.0 && val < threshold){
          if(val < threshold){
            //dataString += " -";
            //dataString += " "+val;
            graph.addEdge(new Node<Integer>(row), new Node<Integer>(col));
          } else if(val >= threshold){
            //dataString += " O";
            //dataString += " "+val;
          }
        }
      }
      //dataString += "\n";
    } // finished reading in the data
    //System.out.print(dataString);
    graph.setGraphFileName(this.alphaStrucID+"_"+this.betaStrucID+".dimacs");
    return graph;
  }

  /*
  * a private helper method to build a string containing all the residue IDs of the nodes
  * in an similarity graph.
  * @param graph the similarity graph
  * @param delimiter the delimiter to use between residue IDs.
  * @return the string that contains all the residue IDs of the residues in this graph separated
  *          by the delimiter
  */
  private String getNodesString(UndirectedGraph<Integer> graph, String delimiter, String[] resIDs){
    Collection<Node<Integer>> nodes = graph.getNodes();
    String ret = "";
    int i = 0;
    for(Node<Integer> node : nodes){
      if(i == 0){
        i++;
        if(node.get() instanceof Integer){
          ret += resIDs[node.get()].trim();
        }
      } else {
        if(node.get() instanceof Integer){
          ret += delimiter+resIDs[node.get()].trim();
        }
      }
    }
    return ret;
  }
}
