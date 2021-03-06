/**
 * Call protein binding sites on RNA with Protect-seq data from multiple control and multiple signal samples
 */
package broad.pda.seq.protection;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.log4j.Logger;

import broad.core.math.MathUtil;
import broad.core.math.Statistics;
import broad.core.parser.CommandLineParser;
import broad.core.parser.StringParser;
import broad.pda.annotation.BEDFileParser;

import nextgen.core.analysis.PeakCaller;
import nextgen.core.annotation.Annotation;
import nextgen.core.annotation.Gene;
import nextgen.core.coordinatesystem.CoordinateSpace;
import nextgen.core.coordinatesystem.TranscriptomeSpace;
import nextgen.core.feature.GeneWindow;
import nextgen.core.model.score.ScanStatisticScore;

/**
 * @author prussell
 *
 */
public final class MultiSampleBindingSiteCaller implements PeakCaller {
	
	private TranscriptomeSpace coord;
	protected Map<String, Collection<Gene>> genes;
	private Map<Gene, Map<Annotation, Double>> crossSampleWindowBindingSiteScores;
	private Map<SampleData, Map<Gene, Map<Annotation, Double>>> singleSampleWindowEnrichmentOverGene;
	protected ArrayList<SampleData> controlSamples;
	protected ArrayList<SampleData> signalSamples;
	protected ArrayList<SampleData> allSamples;
	protected static Logger logger = Logger.getLogger(MultiSampleBindingSiteCaller.class.getName());
	protected int windowSize;
	protected int stepSize;
	private static int DEFAULT_WINDOW_SIZE = 30;
	private static int DEFAULT_STEP_SIZE = 1;
	protected static double EXPRESSION_SCAN_P_VALUE_CUTOFF = 0.05;
	private static double FDR_CUTOFF = 0.01;
	private static int DEFAULT_MAX_PERMUTATIONS = 10000;
	protected int numControls;
	protected int numSignals;
	protected int numSamples;
	protected Random random;
	private Collection<SamplePermutation> sampleIdentityPermutations;
	
	/**
	 * Instantiate with default window size and step size
	 * @param sampleListFile File containing sample list
	 * @param bedFile Bed gene annotation
	 * @throws IOException
	 */
	private MultiSampleBindingSiteCaller(String sampleListFile, String bedFile) throws IOException {
		this(sampleListFile, bedFile, DEFAULT_WINDOW_SIZE, DEFAULT_STEP_SIZE, DEFAULT_MAX_PERMUTATIONS);
	}
	
	/**
	 * Instantiate with custom window size and step size
	 * @param sampleListFile File containing sample list
	 * @param bedFile Bed gene annotation
	 * @param window Window size
	 * @param step Step size
	 * @param maxPermutations Max number of sample identity permutations for window score empirical P value
	 * @throws IOException
	 */
	private MultiSampleBindingSiteCaller(String sampleListFile, String bedFile, int window, int step, int maxPermutations) throws IOException {
		
		// Set basic parameters
		windowSize = window;
		stepSize = step;
		genes = BEDFileParser.loadDataByChr(new File(bedFile));
		coord = new TranscriptomeSpace(genes);
		random = new Random();
		
		// Read sample information
		SampleFileParser p = new SampleFileParser(sampleListFile);
		controlSamples = p.getControlDatasets();
		numControls = controlSamples.size();
		signalSamples = p.getSignalDatasets();
		numSignals = controlSamples.size();
		allSamples = new ArrayList<SampleData>();
		allSamples.addAll(controlSamples);
		allSamples.addAll(signalSamples);
		numSamples = allSamples.size();

		// Initialize score maps
		crossSampleWindowBindingSiteScores = new TreeMap<Gene, Map<Annotation, Double>>();
		singleSampleWindowEnrichmentOverGene = new HashMap<SampleData, Map<Gene, Map<Annotation, Double>>>();
		for(SampleData sample : allSamples) {
			if(!singleSampleWindowEnrichmentOverGene.containsKey(sample)) {
				Map<Gene, Map<Annotation, Double>> m = new TreeMap<Gene, Map<Annotation, Double>>();
				singleSampleWindowEnrichmentOverGene.put(sample, m);
			}
		}
		
		// Store all sample identity permutations for empirical P value of window scores
		sampleIdentityPermutations = new ArrayList<SamplePermutation>();
		Iterator<SamplePermutation> permIter = getIterRandomOrAllSampleIdentityPermutations(maxPermutations);
		while(permIter.hasNext()) {
			sampleIdentityPermutations.add(permIter.next());
		}

	}
	
	/**
	 * Whether the gene is expressed in all control samples at the given significance level
	 * @param gene The gene
	 * @return Whether the gene is expressed by these criteria
	 */
	public boolean isExpressed(Gene gene) {
		for(SampleData control : controlSamples) {
			if(!control.isExpressed(gene)) {
				return false;
			}
		}
		return true;
	}
	
	private void computeSingleSampleWindowEnrichmentsOverGenes() throws IOException {
		logger.info("Computing window enrichments for each sample...");
		for(String chr : genes.keySet()) {
			for(Gene gene : genes.get(chr)) {
				if(!isExpressed(gene)) {
					continue;
				}
				computeSingleSampleWindowEnrichmentOverGene(gene);
			}
		}		
		writeSingleSampleWindowScoresToFileIfNeeded();
		logger.info("Done computing single sample window enrichments.");
	}
	
	/**
	 * Score all genes
	 * @throws IOException 
	 */
	private void scoreGenes() throws IOException {
		computeSingleSampleWindowEnrichmentsOverGenes();
		for(String chr : genes.keySet()) {
			logger.info("Scoring genes on chromosome " + chr);
			for(Gene gene : genes.get(chr)) {
				if(!isExpressed(gene)) {
					logger.info(gene.getName() + " is not expressed. Skipping.");
					continue;
				}
				logger.info("Scoring gene " + gene.getName());
				computeCrossSampleWindowBindingSiteScores(gene);
			}
		}
	}
	
	
	/**
	 * For samples that didn't read window scores from file, write to files
	 * @throws IOException
	 */
	private void writeSingleSampleWindowScoresToFileIfNeeded() throws IOException {
		for(SampleData sample : allSamples) {
			if(!sample.gotWindowScoresFromFile()) {
				sample.writeWindowScoresToFile(this);
			}
		}
		logger.info("Done writing window score files.");
	}
	
	/**
	 * For each sample compute the enrichment of each window over the gene average
	 * Cache the window enrichments
	 * @param gene The gene
	 */
	private void computeSingleSampleWindowEnrichmentOverGene(Gene gene) {
		logger.info("Getting enrichment for each window and each sample...");
		for(SampleData sample : allSamples) {
			Map<Annotation,Double> sampleWindowEnrichments = new TreeMap<Annotation,Double>();
			double geneAvgCoverage = sample.getGeneAverageCoverage(gene);
			logger.info("Sample " + sample.getSampleName() + " Gene average coverage = " + geneAvgCoverage);
			Map<Annotation, ScanStatisticScore> scores = sample.getWindowScores(gene);
			
			if(gene.getSize() < windowSize) {
				logger.info(gene.getName() + " is smaller than window size. Not computing single sample window enrichments.");
				singleSampleWindowEnrichmentOverGene.get(sample).put(gene, sampleWindowEnrichments);
				continue;
			}
			for(Annotation window : scores.keySet()) {
				double windowAvgCoverage = scores.get(window).getAverageCoverage();
				double enrichment = windowAvgCoverage / geneAvgCoverage;
				//logger.info(sample.getSampleName() + "\t" + gene.getName() + "\t" + window.getChr() + ":" + window.getStart() + "-" + window.getEnd() + "\tavg_coverage=" + windowAvgCoverage + "\twindow_enrichment=" + enrichment);
				sampleWindowEnrichments.put(window, Double.valueOf(enrichment));
			}
			singleSampleWindowEnrichmentOverGene.get(sample).put(gene, sampleWindowEnrichments);
		}
	}
	
	/**
	 * Compute binding site score for each window of gene and store scores
	 * @param gene The gene
	 */
	private void computeCrossSampleWindowBindingSiteScores(Gene gene) {
		Map<Annotation, Double> crossSampleBindingSiteScoresThisGene = new TreeMap<Annotation, Double>();
		if(gene.getSize() < windowSize) {
			logger.info(gene.getName() + " is smaller than window size. Not computing window binding site scores.");
			crossSampleWindowBindingSiteScores.put(gene, crossSampleBindingSiteScoresThisGene);
			return;
		}		
		SampleData tmp = allSamples.iterator().next();
		Collection<Annotation> windows = singleSampleWindowEnrichmentOverGene.get(tmp).get(gene).keySet();
		for(Annotation window : windows) {
			double score = windowBindingSiteScore(gene, window, controlSamples, signalSamples);
			crossSampleBindingSiteScoresThisGene.put(window, Double.valueOf(score));
			String e = "";
			for(SampleData control : controlSamples) {
				e += control.getSampleName() + ":" + control.getWindowScores(gene).get(window).getCount() + ":" + singleSampleWindowEnrichmentOverGene.get(control).get(gene).get(window).toString() + "\t";
			}
			for(SampleData signal : controlSamples) {
				e += signal.getSampleName() + ":" + signal.getWindowScores(gene).get(window).getCount() + ":" + singleSampleWindowEnrichmentOverGene.get(signal).get(gene).get(window).toString() + "\t";
			}
			logger.info(gene.getName() + "\t" + window.getChr() + ":" + window.getStart() + "-" + window.getEnd() + "\t" + score + "\t" + e);
		}
		crossSampleWindowBindingSiteScores.put(gene, crossSampleBindingSiteScoresThisGene);
	}
	
	/**
	 * The cross sample binding site score for a single window
	 * T statistic between control and signal samples
	 * Statistic is positive iff mean of signal enrichments is greater than mean of control enrichments
	 * @param gene The parent gene
	 * @param window The window
	 * @param controls Control samples
	 * @param signals Signal samples
	 * @return The binding site score for the window
	 */
	private double windowBindingSiteScore(Gene gene, Annotation window, Collection<SampleData> controls, Collection<SampleData> signals) {
		List<Double> controlEnrichments = new ArrayList<Double>();
		List<Double> signalEnrichments = new ArrayList<Double>();
		for(SampleData control : controls) {
			controlEnrichments.add(singleSampleWindowEnrichmentOverGene.get(control).get(gene).get(window));
		}
		for(SampleData signal : signals) {
			signalEnrichments.add(singleSampleWindowEnrichmentOverGene.get(signal).get(gene).get(window));
		}
		return Statistics.tstat(signalEnrichments, controlEnrichments);
	}
	
	
	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#scoreWindows(java.util.Collection)
	 */
	@Override
	public void scoreWindows(Collection<Annotation> windows) {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}
	
	/**
	 * Nominal P value calculated relative to null distribution of binding site score
	 * Where null distribution is determined by permuting the contol/signal labels of samples
	 * @param gene The gene the window belongs to
	 * @param window The window
	 * @return The nominal P value for the binding site score of the window
	 */
	private double empiricalNominalPval(Gene gene, Annotation window) {
		double windowScore = crossSampleWindowBindingSiteScores.get(gene).get(window).doubleValue();
		double numLess = 0;
		double numMore = 0;
		for(SamplePermutation perm : sampleIdentityPermutations) {
			double score = windowBindingSiteScore(gene, window, perm.getControls(), perm.getSignals());
			if(score > windowScore) numMore++;
			else numLess++;
		}
		return numMore / (numLess + numMore);
	}

	/**
	 * FDR for binding site score of window
	 * @param gene The gene the window belongs to
	 * @param window The window
	 * @return The corrected P value
	 */
	private double windowBindingSiteScoreFDR(Gene gene, Annotation window) {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}
	
	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#filterWindows(java.util.Collection)
	 */
	@Override
	public Collection<Annotation> filterWindows(Collection<Annotation> windows) throws IOException {
		throw new UnsupportedOperationException("TODO");
		// TODO
 	}

	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#mergePeaks(java.util.Collection)
	 */
	@Override
	public Collection<Annotation> mergePeaks(Collection<Annotation> peaks) {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}
	
	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#mergePeaks(java.util.Collection)
	 */	
	@Override
	public Annotation trimPeak(Annotation peak) {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}


	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#writeResults(java.util.Collection, java.lang.String)
	 */
	@Override
	public void writeResults(Collection<Annotation> windows, String out) throws IOException {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}

	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#writeResult(java.util.Collection, java.io.FileWriter)
	 */
	@Override
	public void writeResult(Collection<Annotation> windows, FileWriter writer) throws IOException {
		throw new UnsupportedOperationException("TODO");
		// TODO
	}

	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#setCoordinateSpace(nextgen.core.coordinatesystem.CoordinateSpace)
	 */
	@Override
	public void setCoordinateSpace(CoordinateSpace space) {
		coord = (TranscriptomeSpace) space;
	}

	/* (non-Javadoc)
	 * @see nextgen.core.analysis.PeakCaller#getCoordinateSpace()
	 */
	@Override
	public CoordinateSpace getCoordinateSpace() {
		return coord;
	}
	
	/**
	 * Get a systematic iterator over all possible sample identity permutations
	 * @return An iterator over all permutations of sample identity (control or signal)
	 */
	@SuppressWarnings("unused")
	private Iterator<SamplePermutation> getIterAllSampleIdentityPermutations() {
		return new SystematicSamplePermutationIterator();
	}

	/**
	 * Get an iterator over a fixed number of random sample identity permutations
	 * @param numPermutations The number of permutations to get
	 * @return An iterator over sample identity permutations (control or signal)
	 */
	@SuppressWarnings("unused")
	private Iterator<SamplePermutation> getIterRandomSampleIdentityPermutations(int numPermutations) {
		return new RandomSamplePermutationIterator(numPermutations);
	}
	
	/**
	 * Get an iterator over a set of sample identity permutations
	 * Either a fixed number of random permutations or all possible permutations, whichever is less
	 * @param maxNumPermutations The max number of permutations to get
	 * @return An iterator over at most the max number of sample identity permutations (control or signal)
	 */
	private Iterator<SamplePermutation> getIterRandomOrAllSampleIdentityPermutations(int maxNumPermutations) {
		SystematicSamplePermutationIterator systematicIter = new SystematicSamplePermutationIterator();
		if(systematicIter.getTotalNumPermutations() <= maxNumPermutations) {
			return systematicIter;
		}
		return new RandomSamplePermutationIterator(maxNumPermutations);
	}
	
	/**
	 * Get a random permutation of control and signal identities
	 * @return The random permutation
	 */
	protected SamplePermutation getOneRandomSamplePermutation() {
		ArrayList<Integer> controlPositions = new ArrayList<Integer>();
		int numControlsAssigned = 0;
		while(numControlsAssigned < numControls) {
			Integer randPos = Integer.valueOf(random.nextInt(numSamples));
			if(controlPositions.contains(randPos)) {
				continue;
			}
			controlPositions.add(randPos);
			numControlsAssigned++;
		}
		return new SamplePermutation(controlPositions);
	}

	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		
		CommandLineParser p = new CommandLineParser();
		p.addStringArg("-l", "Sample list file", true);
		p.addStringArg("-b", "Bed file of genes", true);
		p.addIntegerArg("-w", "Window size", false, Integer.valueOf(DEFAULT_WINDOW_SIZE));
		p.addIntegerArg("-s", "Step size", false, Integer.valueOf(DEFAULT_STEP_SIZE));
		p.addIntegerArg("-p", "Max number of sample idenitity permutations for empirical P value of window scores", false, Integer.valueOf(DEFAULT_MAX_PERMUTATIONS));
		p.parse(args);
		String sampleListFile = p.getStringArg("-l");
		String bedFile = p.getStringArg("-b");
		int windowSize = p.getIntegerArg("-w").intValue();
		int stepSize = p.getIntegerArg("-s").intValue();
		int maxPermutations = p.getIntegerArg("-p").intValue();
		
		MultiSampleBindingSiteCaller b = new MultiSampleBindingSiteCaller(sampleListFile, bedFile, windowSize, stepSize, maxPermutations);
		b.scoreGenes();
		
	}

	
	/**
	 * Helper class to parse file containing list of sample types and bam file names
	 * @author prussell
	 *
	 */
	private class SampleFileParser {
		
		private String CONTROL_LABEL = "Control";
		private String SIGNAL_LABEL = "Signal";
		private ArrayList<SampleData> controlData;
		private ArrayList<SampleData> signalData;

		
		public SampleFileParser(String file) throws IOException {
			controlData = new ArrayList<SampleData>();
			signalData = new ArrayList<SampleData>();
			parseFile(file);
		}
		
		/**
		 * Get the control datasets
		 * @return Set of control datasets
		 */
		public ArrayList<SampleData> getControlDatasets() {
			return controlData;
		}
		
		/**
		 * Get the signal datasets
		 * @return Set of signal datasets
		 */
		public ArrayList<SampleData> getSignalDatasets() {
			return signalData;
		}
		
		/**
		 * Parse the sample file and populate data sets
		 * @throws IOException
		 */
		private void parseFile(String fileName) throws IOException {
			FileReader r = new FileReader(fileName);
			BufferedReader b = new BufferedReader(r);
			StringParser s = new StringParser();
			while(b.ready()) {
				
				String line = b.readLine();
				s.parse(line);
				
				if(s.getFieldCount() == 0) continue;
				if(s.getFieldCount() > 2) crashWithHelpMessage();
				
				String sampleType = s.asString(0);
				String bamFile = s.asString(1);
				logger.info("Creating sample data object for bam file " + bamFile);
				SampleData sample = new SampleData(bamFile, genes, windowSize, stepSize, EXPRESSION_SCAN_P_VALUE_CUTOFF);
				
				if(sampleType.equals(CONTROL_LABEL)) {
					controlData.add(sample);
					continue;
				}
				
				if(sampleType.equals(SIGNAL_LABEL)) {
					signalData.add(sample);
					continue;
				}
				
				crashWithHelpMessage();
				
			}
			
			r.close();
			b.close();
		}
		
		/**
		 * Crash and print help message if sample file is invalid
		 */
		private void crashWithHelpMessage() {
			logger.error("Sample file not valid. Each line must be of the form:");
			logger.error(CONTROL_LABEL + "\t<bam_file_name>");
			logger.error("- or -");
			logger.error(SIGNAL_LABEL + "\t<bam_file_name>");
			throw new IllegalArgumentException("Sample file not valid.");
		}
		
		
	}

	
	/**
	 * Iterator over randomly generated sample identity permutations
	 * @author prussell
	 *
	 */
	private class RandomSamplePermutationIterator implements Iterator<SamplePermutation> {
		
		private int numPermutations;
		private int nextPosition;
		
		/**
		 * Construct with number of permutations to generate
		 * @param numToGenerate
		 */
		public RandomSamplePermutationIterator(int numToGenerate) {
			numPermutations = numToGenerate;
			nextPosition = 0;
		}
		
		/**
		 * Get the number of permutations to generate
		 * @return Number of permutations to generate
		 */
		@SuppressWarnings("unused")
		public int getNumToGenerate() {
			return numPermutations;
		}

		@Override
		public boolean hasNext() {
			return nextPosition < numPermutations;
		}

		@Override
		public SamplePermutation next() {
			nextPosition++;
			return getOneRandomSamplePermutation();
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException("TODO");
		}
		
	}
	
	/**
	 * Systematic iterator over all possible sample identity permutations
	 * @author prussell
	 *
	 */
	private class SystematicSamplePermutationIterator implements Iterator<SamplePermutation> {
		
		private int n = numSamples;
		private int r = numControls;
		private int[] currentOneBasedPositions;
		private int[] nextOneBasedPositions;
		private int[] lastOneBasedPositions;
		
		/**
		 * Initialize first set of "control" positions to be 0,1,...,numControls
		 * Set last set to n-r,n-r+1,...,n-1
		 */
		public SystematicSamplePermutationIterator() {
			nextOneBasedPositions = new int[r];
			for(int i = 0; i < r; i++) {
				nextOneBasedPositions[i] = algorithmI(i);
				lastOneBasedPositions[i] = algorithmI(n - r + i);
			}
		}
		
		/**
		 * Translate zero based position to one based position from combination generation algorithm
		 * @param currentPosition Zero based position
		 * @return Position in combination generation algorithm
		 */
		private int algorithmI(int currentPosition) {
			return currentPosition + 1;
		}
		
		/**
		 * Translate one based position from combination generation algorithm to zero based position
		 * @param algorithmI Position in combination generation algorithm
		 * @return Zero based position
		 */
		private int position(int algorithmI) {
			return algorithmI - 1;
		}
		
		/**
		 * Get the total number of all possible permutations
		 * @return The total number of permutations
		 */
		public long getTotalNumPermutations() {
			return MathUtil.binomialCoefficient(numSamples, numControls);
		}

		@Override
		public boolean hasNext() {
			return currentOneBasedPositions != lastOneBasedPositions;
		}

		@Override
		public SamplePermutation next() {
			
			currentOneBasedPositions = nextOneBasedPositions;
			// Increment next positions according to lexicographic order
			int k = 0;
			for(int i = 0; i < r; i++) {
				if(nextOneBasedPositions[i] < n - r + algorithmI(i)) {
					k = i;
				}
			}
			nextOneBasedPositions[k]++;
			for(int i = k + 1; i < r; i++) {
				nextOneBasedPositions[i] = nextOneBasedPositions[i-1]+1;
			}
			
			ArrayList<Integer> rtrnPositions = new ArrayList<Integer>();
			for(int i = 0; i < r; i++) {
				rtrnPositions.add(new Integer(position(currentOneBasedPositions[i])));
			}
			
			return new SamplePermutation(rtrnPositions);
			
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException("TODO");
		}
		
	}
	
	/**
	 * A permutation of sample identities (control or signal)
	 * @author prussell
	 *
	 */
	private class SamplePermutation {
		
		private Collection<SampleData> controls;
		private Collection<SampleData> signals;
		private ArrayList<Integer> controlPositionsInRealSampleList;
		private ArrayList<Integer> signalPositionsInRealSampleList;
		
		/**
		 * Construct with positions of "control" and "signal" samples from the real sample list
		 * @param labeledControlsPositionsInRealSampleList Positions in real sample list of samples to label as controls
		 */
		public SamplePermutation(ArrayList<Integer> labeledControlsPositionsInRealSampleList) {
			controlPositionsInRealSampleList = labeledControlsPositionsInRealSampleList;
			signalPositionsInRealSampleList = getSignalPositionsInRealSampleList();
			controls = new ArrayList<SampleData>();
			for(int i=0; i < controlPositionsInRealSampleList.size(); i++) {
				controls.add(allSamples.get(controlPositionsInRealSampleList.get(i).intValue()));
			}
			signals = new ArrayList<SampleData>();
			for(int i=0; i < signalPositionsInRealSampleList.size(); i++) {
				signals.add(allSamples.get(signalPositionsInRealSampleList.get(i).intValue()));
			}
		}
		
		/**
		 * Get positions of "signal" labeled samples in real sample list
		 * Based on the samples labeled "control"
		 * @return Positions of "signal" labeled samples in real sample list
		 */
		private ArrayList<Integer> getSignalPositionsInRealSampleList() {
			ArrayList<Integer> rtrn = new ArrayList<Integer>();
			for(int i=0; i<numSamples; i++) {
				if(!controlPositionsInRealSampleList.contains(Integer.valueOf(i))) {
					rtrn.add(Integer.valueOf(i));
				}
			}
			return rtrn;
		}
		
		/**
		 * Get the samples labeled as controls in this permutation
		 * @return The controls in this permutation
		 */
		public Collection<SampleData> getControls() {
			return controls;
		}
		
		/**
		 * Get the samples labeled as signals in this permutation
		 * @return The signals in this permutation
		 */
		public Collection<SampleData> getSignals() {
			return signals;
		}
		
		
	}


}
