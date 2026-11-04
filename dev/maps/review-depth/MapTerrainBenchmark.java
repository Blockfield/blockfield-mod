package com.lunares.blockfield.server.core;
import java.util.Arrays;
public class MapTerrainBenchmark {
 public static void main(String[] args) {
  int width=703; int[] heights=new int[width*width]; byte[] colors=new byte[heights.length*3];
  for(int p=0;p<heights.length;p++)heights[p]=(p%width/16%2)*6+(p/width/32%2)*4;
  for(int pass=0;pass<3;pass++) {
   Arrays.fill(colors,(byte)160); long start=System.nanoTime(),max=0;
   for(int row=0;row<width;row+=2048/width) {long t=System.nanoTime();MapTerrain.shade(colors,heights,width,1,1,row,2048/width);max=Math.max(max,System.nanoTime()-t);}
   System.out.printf("703x703 bake %.1f ms, max slice %.2f ms%n",(System.nanoTime()-start)/1e6,max/1e6);
  }
 }
}
