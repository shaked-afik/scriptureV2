package nextgen.core.annotation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;


import nextgen.core.annotation.Annotation.Strand;

/**
 * @author engreitz
 * This abstract class contains generic implementations of Annotation functions that rely only 
 * on other Annotation functions.
 */
public abstract class AbstractAnnotation implements Annotation {
	

	public static final int MAX_DISTANCE = 1000000000;
	
	public int size() {
		return length();
	}
	
	public int getSize() {
		return length();
	}
	
	public int getMidpoint(){
		return (getEnd()-getStart())/2;
	}
	
	public Strand getStrand() {
		return getOrientation();
	}
	
	public boolean hasOrientation() {
		return (getOrientation() != null && getOrientation() != Strand.UNKNOWN);
	}
	
	/**
     * Returns true if the alignment is negative stranded
     * @return
     */
	public boolean isNegativeStrand() {
		if (hasOrientation() && getOrientation() == Strand.NEGATIVE) 
			return true;
		else 
			return false;
	}
	
	public static Strand getStrand (String orientation){
		
		if(orientation.equalsIgnoreCase("+")){return Strand.POSITIVE;}
		else if(orientation.equalsIgnoreCase("-")){return Strand.NEGATIVE;}
		return Strand.UNKNOWN;
	}
	
	public String getChr() {
		return getReferenceName();
	}
	
	public int getOrientedStart() {
		if (hasOrientation() && getStrand() == Strand.NEGATIVE) {
			return getEnd();
		} else {
			return getStart();
		}
	}
	
	
	public int getOrientedEnd() {
		if (hasOrientation() && getStrand() == Strand.NEGATIVE) {
			return getStart();
		} else {
			return getEnd();
		}
	}
	
	
	public int getLengthOnReference() {
		return (getEnd() - getStart());
	}
	
	
	public int getReferenceCoordinateAtPosition(int positionInAnnotation) {
		return getReferenceCoordinateAtPosition(positionInAnnotation, false);
	}
	
	
	public int getPositionAtReferenceCoordinate(int referenceCoordinate) {
		return getPositionAtReferenceCoordinate(referenceCoordinate, false);
	}
	
	
	public void setOrientation(char orientation) {
		if (orientation == '+') {
			setOrientation(Strand.POSITIVE);
		} else if (orientation == '-') {
			setOrientation(Strand.NEGATIVE);
		} else if (orientation == '*' || orientation == '.') {
			setOrientation(Strand.UNKNOWN);
		} else {
			setOrientation(Strand.UNKNOWN);
			//throw new IllegalArgumentException("Invalid strand: " + orientation);
		}
	}
	
	
	public void expand(int deltaStart, int deltaEnd) {
		setStart(getStart() - deltaStart);
		setEnd(getEnd() - deltaEnd);
	}
	
	
	public void trim(int deltaStart, int deltaEnd) {
		
		if(getStrand().equals(Strand.NEGATIVE)) {
			int tmpStart = deltaStart;
			deltaStart = deltaEnd;
			deltaEnd = tmpStart;
		}
		
		setStart(getReferenceCoordinateAtPosition(deltaStart,true));
		setEnd(getReferenceCoordinateAtPosition(length()-deltaEnd, true));
		
	}
	
	public boolean fullyContains(Annotation other) {
		return contains(other);
	}
	
	
	public boolean overlaps(Annotation other) {
		return overlaps(other, 0);
	}
	
	public boolean overlaps(Annotation other, int buffer) {
		return overlaps(other, buffer, false);
	}
	
	public boolean overlapsStranded(Annotation other) {
		return overlaps(other, 0, true);
	}
	
	public boolean overlaps(Annotation other, boolean considerOrientation) {
		return overlaps(other, 0, considerOrientation);
	}
	
	public boolean overlaps(Annotation other, int buffer, boolean considerOrientation) {
		// Note: this function is overridden in BasicAnnotation to directly use the CompoundInterval already stored
		if (considerOrientation && (getOrientation() != other.getOrientation())) return false;
		if (!getReferenceName().equalsIgnoreCase(other.getReferenceName())) return false;
		return intervalFromAnnotation(this).overlaps(intervalFromAnnotation(other));
	}
	
	private CompoundInterval intervalFromAnnotation(Annotation a) {
		CompoundInterval interval = new CompoundInterval();
		for (Annotation block : a.getBlocks())
			interval.addInterval(block.getStart(), block.getEnd());
		return interval;
	}
	
	public boolean overlaps(Collection<? extends Annotation> others) {
		return overlaps(others, 0);
	}
	
	public boolean overlaps(Collection<? extends Annotation> others, int buffer) {
		boolean rtrn = false;
		for (Annotation o : others ) {
			if (overlaps(o, buffer)) {
				rtrn = true;
				break;
			}
		}
		return rtrn;
	}
	
	public String toUCSC() {
		return getReferenceName() + ":" + getStart() + "-" + getEnd();
	}
	
	
	public String toBED() { 
		return toBED(0, 0, 0);
	}
	
	@Override
	public String toBED(int r, int g, int b){
		if(r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
			throw new IllegalArgumentException("RGB values must be between 0 and 255");
		}
		String rgb = r + "," + g + "," + b;
		List<? extends Annotation> exons = getBlocks();
		//String rtrn=this.getChr()+"\t"+this.getStart()+"\t"+this.getEnd()+"\t"+(name == null ? toUCSC() : this.name)+"\t"+getCountScore()+"\t"+orientation+"\t"+start+"\t"+stop+"\t"+rgb+"\t"+exons.size();
		String rtrn=getReferenceName()+"\t"+getStart()+"\t"+getEnd()+"\t"+(getName() == null ? toUCSC() : getName())+"\t"+getScore()+"\t"+getOrientation()+"\t"+getStart()+"\t"+getEnd()+"\t"+rgb+"\t"+exons.size();
		String sizes="";
		String starts="";
		for(Annotation exon : exons){
			sizes=sizes+(exon.length())+",";
			starts=starts+(exon.getStart()-getStart())+",";
		}
		rtrn=rtrn+"\t"+sizes+"\t"+starts;
		return rtrn;
	}
	
	public String toShortBED() {
		return getReferenceName() + "\t" + getStart() + "\t" + getEnd();
	}
	
	public String toBEDGraph() {
		return getReferenceName() + "\t" + getStart() + "\t" + getEnd() + "\t" + getScore();
	}
	
	public int getDistanceTo(Annotation other) {
		int dist = 0;
		if(!getReferenceName().equals(other.getReferenceName())) {
			dist = MAX_DISTANCE;
		} else if (!overlaps(other)) {
			if (getStart() >= other.getEnd()) {
				dist = getStart() - other.getEnd();
			} else {
				dist = other.getStart() - getEnd();
			}
		}
		return dist;
	}

	/**
	 * Return the Annotation object cigar string
	 * @return
	 */
	public String toCigar() {
		String rtrn="";
		List<? extends Annotation> exons=getBlocks();
		for(int i=0; i<exons.size(); i++){
			Annotation exon= exons.get(i);
			if (i!=0) {
				Annotation previous= exons.get(i-1);
				Annotation intron= new BasicAnnotation(exon.getReferenceName(), previous.getEnd(), exon.getStart());
				if (intron.length() > 0) {
					rtrn = rtrn + (intron.length())+"N";
				}
			}
			rtrn=rtrn+(exon.length())+"M";
		}
		return rtrn;
	}
	
	
	public List<Annotation> intersect(List<? extends Annotation> others) {
		List<Annotation> result = new ArrayList<Annotation>();
		for (Annotation other : others) 
			result.add(intersect(other));
		return result;
	}
	
	public int compareTo (Annotation other) {
		return compareToAnnotation(other);
	}
	
	public int compareToAnnotation(Annotation b) {
		if (equals(b)) {return 0;} 
		
		//first sort by chromosome
		int comp = getReferenceName().compareTo(b.getReferenceName());
		if(comp!=0){return comp;}
		
		//second sort by start coordinate
		comp=getStart() - b.getStart();
		if(comp!=0){return comp;}
		
		//third sort by end coordinate
		comp=getEnd()-b.getEnd();
		if(comp!=0){return comp;}
		
		//fourth sort by strand
		comp=getOrientation().compareTo(b.getOrientation());
		if(comp!=0){return comp;}
		
		//Fifth sort by number of blocks
		comp=getBlocks().size()-b.getBlocks().size();
		if(comp!=0){return comp;}
		
		//Sixth sort by position of the blocks (in order scan)
		if(b.getBlocks().size()>1){
			for(int i=0; i<getBlocks().size(); i++){
				Annotation ann1=getBlocks().get(i);
				Annotation ann2=b.getBlocks().get(i);
				comp=ann1.compareTo(ann2);
				if(comp!=0){return comp;}
			}
		}
		
		return 0;
	}


	public static Strand getStrand(char orientation) {
		if(orientation=='+'){return Strand.POSITIVE;}
		else if(orientation=='-'){return Strand.NEGATIVE;}
		return Strand.UNKNOWN;
	}
	
	
	@Override
	public boolean equals(Annotation a) {
		return equals(a, true); //default will force the strands to be equal
	}
	
	@Override
	public boolean equals (Annotation a, boolean useOrientation){
		//simple comparison
		if(!a.getReferenceName().equalsIgnoreCase(getReferenceName())){return false;}
		if(a.getStart()!=getStart()){return false;}
		if(a.getEnd()!=getEnd()){return false;}
		if(useOrientation){
			if(!a.getOrientation().equals(getOrientation())){return false;}
		}
		if(a.getBlocks().size() != getBlocks().size()){return false;}
		if(a.getBlocks().size()>1){
			//compare blocks
			for(int i=0; i<a.getBlocks().size(); i++){
				boolean blockMatch=false;
				for(int j=0; j<getBlocks().size(); j++){
					Annotation ann1=a.getBlocks().get(i);
					Annotation ann2=getBlocks().get(j);
					if(ann1.getStart()==ann2.getStart() && ann1.getEnd()==ann2.getEnd()){blockMatch=true;}
				}
				if(!blockMatch){return false;}
			}
		}
		return true;
	}
	
	/**
	 * Returns the start position of this window into SAM coordinate space
	 * SAM coordinates are 1-based and inclusive whereas all of our objects are 0-based exclusive
	 * @return
	 */
	@Override
	public int getSAMStart(){
		return getStart()+1;
	}
	
	/**
	 * Returns the start position of this window into SAM coordinate space
	 * SAM coordinates are 1-based and inclusive whereas all of our objects are 0-based exclusive
	 * @return
	 */
	@Override
	public int getSAMEnd(){
		return getEnd();
	}
	
	
}
