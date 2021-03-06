package nextgen.core.annotation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

import nextgen.core.annotation.Annotation.Strand;

import broad.pda.datastructures.Alignments;
import broad.core.error.ParseException;


/**
 * @author engreitz
 * Coordinate system:  0-based, including first base but not the last
 */
public class BasicAnnotation extends AbstractAnnotation {
	protected CompoundInterval blocks = new CompoundInterval();
	private String referenceName;
	private Strand orientation = Strand.UNKNOWN;
	private String name = null;
	private double score = 0;
	
	/********************************************************************************
	 * CONSTRUCTORS
	 */
	
	public BasicAnnotation() {
		throw new UnsupportedOperationException("This is a stupid constructor");
	}
	
	public BasicAnnotation(String ucsc) {
		String ref=ucsc.split(":")[0];
		String start=ucsc.split(":")[1].split("-")[0];
		String end=ucsc.split(":")[1].split("-")[1];
		setReferenceName(ref);
		blocks.addInterval(new Integer(start), new Integer(end));
	}
	
	public BasicAnnotation(String referenceName, int start, int end, Strand orientation, String name) {
		setName(name);
		setReferenceName(referenceName);
		setOrientation(orientation);
		try {
			blocks.addInterval(start, end);
		} catch(Exception e) {
			e.printStackTrace();
			throw new IllegalArgumentException(toBED());
		}
	}
	
	public BasicAnnotation(String referenceName, Strand orientation, String name, Collection<? extends Annotation> blocks) {
		setName(name);
		setReferenceName(referenceName);
		setOrientation(orientation);
		addBlocks(blocks);
	}
	
	public BasicAnnotation(String referenceName, int start, int end) {
		this(referenceName, start, end, Strand.UNKNOWN);
	}
	
	public BasicAnnotation(String referenceName, int start, int end, Strand orientation) {
		this(referenceName, start, end, orientation, "");
	}
	
	public BasicAnnotation(String referenceName, int start, int end, String orientation) {
		this(referenceName, start, end, AbstractAnnotation.getStrand(orientation));
	}
	
	public BasicAnnotation(BasicAnnotation other) {
		setReferenceName(other.getReferenceName());
		setOrientation(other.getOrientation());
		setName(other.name);
		this.blocks = new CompoundInterval(other.blocks);
		setScore(other.getScore());
	}
	
	public BasicAnnotation(Annotation other) {
		setReferenceName(other.getReferenceName());
		setOrientation(other.getOrientation());
		setName(other.getName());
		addBlocks(other);
		setScore(other.getScore());
	}
	
	public BasicAnnotation(Collection<? extends Annotation> blocks) {
		if (blocks.size() == 0) throw new IllegalArgumentException("cannot create empty BasicAnnotation");
		Iterator<? extends Annotation> itr = blocks.iterator();
		Annotation first = itr.next();
		setReferenceName(first.getReferenceName());
		setOrientation(first.getOrientation());
		setName(first.getName());
		addBlocks(first);
		while (itr.hasNext()) addBlocks(itr.next());
	}
	
	public BasicAnnotation(Collection<? extends Annotation> blocks, Strand orientation, String name) {
		this(blocks);
		setName(name);
		setOrientation(orientation);
	}
	
	public BasicAnnotation(Collection<? extends Annotation> blocks, String name) {
		this(blocks);
		setName(name);
	}
	
	public BasicAnnotation(String referenceName, CompoundInterval blocks, Strand orientation, String name) {
		setReferenceName(referenceName);
		this.blocks = blocks;
		setOrientation(orientation);
		setName(name);
	}
	
	public BasicAnnotation(String referenceName, CompoundInterval blocks, Strand orientation) {
		this(referenceName, blocks, orientation, "");
	}
	
	public BasicAnnotation(String referenceName, CompoundInterval blocks) {
		this(referenceName, blocks, Strand.UNKNOWN);
	}
	

	public Annotation copy() {
		return new BasicAnnotation(this);
	}
	
	public static Annotation createFromUCSC(String ucsc) {
		String [] firstSplit = ucsc.split(":");
		String [] secondSplit = firstSplit[1].split("-");
		return new BasicAnnotation(firstSplit[0], Integer.valueOf(secondSplit[0]), Integer.valueOf(secondSplit[1]));
	}
	
	
	public static class Factory implements AnnotationFactory<BasicAnnotation> {
		@Override
		public BasicAnnotation create(String[] rawFields) throws ParseException {
			return new BasicAnnotation(rawFields[0], Integer.valueOf(rawFields[1]), Integer.valueOf(rawFields[2]));
		}
	}
	
	/********************************************************************************
	 * QUERY METHODS
	 */
	public int getStart() {
		return blocks.getStart();
	}
	
	public int getEnd() {
		return blocks.getEnd();
	}

	public String getReferenceName() {
		return referenceName;
	}
	
	public String getName() {
		return name;  // even if it's null! if we override this when null it makes things complicated
	}
	
	public Strand getOrientation(){
		return orientation;
	}
	
	public boolean isUnoriented() {
		return !Strand.NEGATIVE.equals(getOrientation()) && !Strand.POSITIVE.equals(getOrientation()) ;
	}
	
	public List<? extends Annotation> getBlocks() {
		return getBlocks(false);
	}
		
	public List<? extends Annotation> getBlocks(boolean oriented) {
		List<Annotation> list = new LinkedList<Annotation>();
		for (SingleInterval block : blocks.getBlocks()) {
			list.add(new BasicAnnotation(referenceName, block.getStart(), block.getEnd(), orientation));
		}
		if (oriented && orientation == Strand.NEGATIVE) {
			Collections.reverse(list);
		}
		return list;
	}

	public int numBlocks() {
		return blocks.numBlocks();
	}
	
	public int length() {
		return blocks.length();
	}
	
	public int getReferenceCoordinateAtPosition(int positionInAnnotation, boolean ignoreOrientation) {
		if (getStrand() == Strand.NEGATIVE && !ignoreOrientation) {
			positionInAnnotation = length() - positionInAnnotation; //TODO who took out the -1? 
		}
		return blocks.getCoordinateAtPosition(positionInAnnotation);
	}
	
	public int getPositionAtReferenceCoordinate(int referenceCoordinate, boolean ignoreOrientation) {
		int positionInAnnotation = blocks.getPositionAtCoordinate(referenceCoordinate);
		if (getStrand() == Strand.NEGATIVE && !ignoreOrientation) {
			positionInAnnotation = length() - positionInAnnotation; //TODO who took out the -1?  
		}
		return positionInAnnotation;
	}
	
	public double getScore() { 
		return score;
	}
	
	public String toString() {
		return toBED();
	}

	/********************************************************************************
	 * SETTING METHODS
	 */
	
	public void setStart(int start) {
		blocks.setStart(start);
	}
	
	public void setEnd(int end) {
		blocks.setEnd(end);
	}

	
	public void setOrientation(Strand orientation) {
		if(orientation==null){orientation=Strand.UNKNOWN;}
		this.orientation = orientation;
	}
	
	public void setOrientedStart(int orientedStart) {
		// TODO is this the correct 0-based behavior?
		if (orientation == Strand.NEGATIVE) {
			setEnd(orientedStart);
		} else {
			setStart(orientedStart);
		}
	}
	
	public void setOrientedEnd(int orientedEnd) {
		if (orientation == Strand.NEGATIVE) {
			setStart(orientedEnd);
		} else {
			setEnd(orientedEnd);
		}
	}
	
	public void setReferenceName(String refName) {
		this.referenceName = refName != null ? refName.intern() : null;
	}
	
	public void setName(String name) {
		this.name =  name;
	}
	
	public void setScore(double score) {
		this.score = score;
	}
	
	public void addBlocks(Annotation block) {
		if (numBlocks() > 0 && !block.getReferenceName().equals(getReferenceName())) {
			throw new IllegalArgumentException("Reference names must match: "+block.getReferenceName()+" "+getReferenceName()+" "+getName());
		}
		// strand of the new block is ignored
		
		// recursively add blocks if the Annotation has multiple blocks
		if (block.numBlocks() > 1) {
			for (Annotation a : block.getBlocks()) {
				addBlocks(a);
			}
		} else {
			if (numBlocks() == 0) {
				setReferenceName(block.getReferenceName());
			}
			blocks.addInterval(block.getStart(), block.getEnd());
		}
	}

	public void addBlocks(Collection<? extends Annotation> blocks) {
		for(Annotation block: blocks){addBlocks(block);}
	}
	
	
	public void shift(int delta) {
		blocks.shift(delta);
	}
	
	public void moveToCoordinate(int coordinateInReference) {
		blocks.moveToCoordinate(coordinateInReference);
	}
	
	/********************************************************************************
	 * COMPARISON METHODS
	 */
	
	@Override
	public boolean overlaps(Annotation other, int buffer, boolean considerOrientation) {
		if (BasicAnnotation.class.isInstance(other)) {
			return overlaps((BasicAnnotation) other, buffer, considerOrientation);
		} else {
			BasicAnnotation basic = new BasicAnnotation(other);
			return overlaps(basic, buffer, considerOrientation);
		}
	}
	
	public boolean overlaps(BasicAnnotation other, int buffer, boolean considerOrientation) {
		if (considerOrientation && (getOrientation() != other.getOrientation())) return false;
		if (!getReferenceName().equalsIgnoreCase(other.getReferenceName())) return false;
		return blocks.overlaps(other.blocks, buffer);
	}
	
	public int getOverlap(Annotation other) {
		int overlap = 0;
		if (getReferenceName().equals(other.getReferenceName())) {
			BasicAnnotation basic = new BasicAnnotation(other);
			overlap = blocks.intersect(basic.blocks).length();
		}
		return overlap; 
	}
	
	public boolean contains(Annotation other) {
		boolean result = false;
		if (getReferenceName().equals(other.getReferenceName())) {
			BasicAnnotation basic = new BasicAnnotation(other);
			result = blocks.contains(basic.blocks);
		}
		return result;
	}
	
	public Annotation union(Annotation other) {
		if(!getReferenceName().equals(other.getReferenceName())) {
			throw new IllegalArgumentException("Cannot merge annotations on different reference sequences.");
		}
		BasicAnnotation basic = new BasicAnnotation(other);
		CompoundInterval newBlocks = blocks.union(basic.blocks);
		Annotation result = new BasicAnnotation(getReferenceName(), newBlocks, getStrand());
		if (getOrientation() == other.getOrientation()) result.setOrientation(getOrientation());
		return result;
	}
	
	public Annotation intersect(Annotation other) {
		Annotation result = null;
		if (getReferenceName().equals(other.getReferenceName())) {
			BasicAnnotation basic = new BasicAnnotation(other);
			CompoundInterval newBlocks = blocks.intersect(basic.blocks);
			
			// JE 1/17/13 changed to return null if empty
			if (newBlocks.length() > 0) result = new BasicAnnotation(getReferenceName(), newBlocks, getStrand());
		}
		return result;
	}


	@Override
	public List<Annotation> disect(Annotation a) {
		/*List<Annotation> rtrn=new ArrayList<Annotation>();
		
		//We will split the current annotation by the block of the other
		List<? extends Annotation> blocks=a.getBlocks();
		for(Annotation block: blocks){
			if(getStart()<block.getStart()){
				rtrn.addAll(split(getStart(), block.getStart()).getBlocks());
			}
			if(block.getStart()<block.getEnd()){
				rtrn.addAll(split(block.getStart(), block.getEnd()).getBlocks());
			}
			if(block.getEnd()<getEnd()){
				rtrn.addAll(split(block.getEnd(), getEnd()).getBlocks());
			}
		}
		
		return rtrn;*/
		
		throw new UnsupportedOperationException("TODO");
	}


	private Annotation split(int start, int end) {
		return this.intersect(new Alignments(this.getChr(), start, end));
		
		/*CompoundInterval newInterval=new CompoundInterval(blocks);
		newInterval.setStart(start);
		newInterval.setEnd(end);
		Annotation rtrn=new BasicAnnotation(referenceName, newInterval, orientation, name);
		return rtrn;*/
	}

	@Override
	public List<Annotation> disect(List<? extends Annotation> disectors) {
		List<Annotation> rtrn=new ArrayList<Annotation>();
		
		for(Annotation annotation: disectors){
			rtrn.addAll(disect(annotation));
		}
		return rtrn;
	}


	@Override
	public Annotation minus(Annotation other) {
		BasicAnnotation basic=new BasicAnnotation(other);
		CompoundInterval newBlocks =this.blocks.minus(basic.blocks);
		BasicAnnotation rtrn= new BasicAnnotation(getReferenceName(), newBlocks, getStrand());
		//List<Annotation> list=new ArrayList<Annotation>();
		//list.add(rtrn);
		return rtrn;
		
		//throw new UnsupportedOperationException("TODO");
	}


	@Override
	public Annotation minus(Collection<? extends Annotation> others) {
		BasicAnnotation basic=new BasicAnnotation(others);
		return minus(basic);
	}


	@Override
	public void stitchTo(Annotation next) {
		throw new UnsupportedOperationException("TODO");
	}

	@Override
	public Annotation complement() {
		CompoundInterval interval=this.blocks.complement();
		BasicAnnotation rtrn=new BasicAnnotation(this.referenceName, interval, this.orientation, this.name);
		return rtrn;
	}

	@Override
	/**
	 * The BasicAnnotation will return the gaps themselves
	 */
	public Collection<? extends Annotation> getSpliceConnections() {
		Collection<Annotation> rtrn=new TreeSet<Annotation>();
		//if has exons
		if(this.getBlocks().size()>1){
			rtrn.addAll(this.complement().getBlocks());
		}
		return rtrn;
	}


	
	
	/********************************************************************************
	 * PRIVATE UTILITY METHODS
	 */
	
}
