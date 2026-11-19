package org.taniwha.privacy.pooling;

import java.security.SecureRandom;
import java.util.*;

// Common grids only coarsen released intervals; they never split hidden counts.
final class SecurePoolDomains {
    private SecurePoolDomains() {}

    static PrivatePoolingDomain common(List<PrivatePoolingDomain> domains) {
        if (domains.isEmpty()) throw new IllegalArgumentException("No feature domains");
        domains.forEach(PrivatePoolingDomain::validate);
        String type=domains.get(0).type();
        if (domains.stream().anyMatch(d->!d.type().equals(type))) throw new IllegalArgumentException("Feature types differ");
        boolean missing=domains.stream().anyMatch(PrivatePoolingDomain::missingAllowed);
        if (type.equals("categorical")) {
            var labels=domains.stream().flatMap(d->d.categories().stream()).distinct().sorted().toList();
            return checked(new PrivatePoolingDomain(null,labels,null,type,missing,
                    domains.stream().anyMatch(PrivatePoolingDomain::unlistedCategoriesAllowed)));
        }
        if (domains.stream().allMatch(d->d.values()!=null)) {
            var points=domains.stream().flatMap(d->d.values().stream()).distinct().sorted().toList();
            if (points.size()<=128) {
                var edges=new ArrayList<Double>();
                edges.add(Math.max(-1e9,points.get(0)-.5));
                for(int i=1;i<points.size();i++) edges.add((points.get(i-1)+points.get(i))/2);
                edges.add(Math.min(1e9,points.get(points.size()-1)+.5));
                return checked(new PrivatePoolingDomain(edges,null,points,type,missing,true));
            }
        }
        var intervals=domains.stream().filter(d->d.values()==null).toList();
        double min=domains.stream().mapToDouble(d->d.values()==null?d.boundaries().get(0):d.values().get(0)).min().orElseThrow();
        double max=domains.stream().mapToDouble(d->d.values()==null?d.boundaries().get(d.boundaries().size()-1):d.values().get(d.values().size()-1)).max().orElseThrow();
        var candidates=new TreeSet<Double>(); candidates.add(min);candidates.add(max);
        intervals.forEach(d->candidates.addAll(d.boundaries()));
        // For many discrete points use a coarse common grid, without inventing exact resolution.
        if(intervals.isEmpty()) {
            double width=Math.pow(2,Math.ceil(Math.log(Math.max(max-min,1)/32)/Math.log(2)));
            double lower=Math.floor(min/width)*width,upper=Math.ceil(max/width)*width;
            candidates.clear();
            for(double edge=lower;edge<=upper;edge+=width) candidates.add(Math.max(-1e9,Math.min(1e9,edge)));
        }
        var edges=candidates.stream().filter(edge->intervals.isEmpty() || (edge>=min && edge<=max && intervals.stream().allMatch(d->
                edge<=d.boundaries().get(0) || edge>=d.boundaries().get(d.boundaries().size()-1)
                        || d.boundaries().stream().anyMatch(value->close(value,edge))))).toList();
        return checked(new PrivatePoolingDomain(edges,null,null,type,missing,true));
    }

    static SecurePoolFeatureOffer offer(PrivatePoolingHistogram input, PrivatePoolingDomain target) {
        int[] fanIn=fanIn(input.domain(),target);
        int width=Math.max(1,Arrays.stream(fanIn).max().orElse(1));
        List<Double> base=input.noiseComponents()==null?List.of(input.noiseVariance()):input.noiseComponents();
        var components=new ArrayList<Double>();
        for(int i=0;i<width;i++) components.addAll(base);
        if(components.size()>64) throw new IllegalArgumentException("Common grid needs too many noise components");
        return new SecurePoolFeatureOffer(target,components.stream().mapToDouble(Double::doubleValue).sum(),List.copyOf(components));
    }

    static long[] rebin(PrivatePoolingHistogram input, PrivatePoolingDomain target, SecureRandom random) {
        long[] result=new long[target.size()];
        int[] fanIn=fanIn(input.domain(),target);
        for(int cell=0;cell<input.counts().size();cell++) {
            int index=targetCell(input.domain(),cell,target);
            result[index]=Math.addExact(result[index],input.counts().get(cell));
        }
        int width=Math.max(1,Arrays.stream(fanIn).max().orElse(1));
        var variances=input.noiseComponents()==null?List.of(input.noiseVariance()):input.noiseComponents();
        // Equalize noise per output cell using independent data-free padding.
        // This post-processing never regenerates the cached data-dependent release.
        for(int cell=0;cell<result.length;cell++) {
            if(target.structuralZero(cell)) {
                if(result[cell]!=0) throw new IllegalArgumentException("Invalid structural zero");
                continue;
            }
            for(int pad=fanIn[cell];pad<width;pad++) for(double variance:variances) {
                double q=variance/(variance+1+Math.sqrt(2*variance+1));
                int numerator=(int)Math.round(q*1024);
                if(numerator<=0 || numerator>=1024 || Math.abs(q-numerator/1024.)>1e-10)
                    throw new IllegalArgumentException("Unsupported cached noise mechanism");
                result[cell]=Math.addExact(result[cell],new PrivateHistogramNoise(random,numerator,1024).release(0));
            }
        }
        return result;
    }

    private static int[] fanIn(PrivatePoolingDomain source,PrivatePoolingDomain target) {
        int[] result=new int[target.size()];
        for(int cell=0;cell<source.size();cell++) if(!source.structuralZero(cell)) result[targetCell(source,cell,target)]++;
        return result;
    }

    private static int targetCell(PrivatePoolingDomain source,int cell,PrivatePoolingDomain target) {
        if(cell==source.size()-1) return target.size()-1;
        if(source.categories()!=null) {
            if(cell==source.size()-2) return target.size()-2;
            int index=target.categories().indexOf(source.categories().get(cell));
            if(index<0) throw new IllegalArgumentException("Missing public category");
            return index;
        }
        if(source.values()!=null) {
            double value=source.values().get(cell);
            if(target.values()!=null) {
                int index=target.values().indexOf(value);
                if(index<0) throw new IllegalArgumentException("Missing public point");
                return index;
            }
            return bin(value,target.boundaries());
        }
        int index=bin((source.boundaries().get(cell)+source.boundaries().get(cell+1))/2,target.boundaries());
        if(source.boundaries().get(cell)<target.boundaries().get(index) && !close(source.boundaries().get(cell),target.boundaries().get(index))
                || source.boundaries().get(cell+1)>target.boundaries().get(index+1) && !close(source.boundaries().get(cell+1),target.boundaries().get(index+1)))
            throw new IllegalArgumentException("Cannot split a protected interval");
        return index;
    }

    private static int bin(double value,List<Double> edges) {
        int found=Collections.binarySearch(edges,value);
        return Math.max(0,Math.min(edges.size()-2,found<0?-found-2:found));
    }
    private static boolean close(double a,double b) {return Math.abs(a-b)<=16*Math.ulp(Math.max(1,Math.max(Math.abs(a),Math.abs(b))));}
    private static PrivatePoolingDomain checked(PrivatePoolingDomain domain) {domain.validate();return domain;}
}
