package org.mapdb;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeMap;

import static org.junit.Assert.*;

@SuppressWarnings({ "unchecked", "rawtypes" })
public class HTreeMap2Test {

    Engine engine = DBMaker.newMemoryDB().cacheDisable().asyncWriteDisable().makeEngine();

    DB db = new DB(engine);

    void printMap(HTreeMap m){
        System.out.println(toString(m.segmentRecids, engine));
    }

    static String toString(long[] rootRecids, Engine engine){
        String s = "Arrays.asList(\n";
        for(long r:rootRecids){
            s+= (r==0)?null:recursiveToString(r,"", engine);
        }
        //s=s.substring(0,s.length()-2);
        s+=");";
        return s;
    }

    protected static Serializer serializer = DBMaker.newTempHashMap().LN_SERIALIZER;

    static private String recursiveToString(long r, String prefix, Engine engine) {
        prefix+="  ";
        String s="";
        long[][] nn = engine.get(r, HTreeMap.DIR_SERIALIZER);
        if(nn==null){
            s+=prefix+"null,\n";
        }else{
            s+= prefix+"Arrays.asList(\n";
        for(long[] n:nn){
            if(n==null){
                s+=prefix+"  null,\n";
            }else{
                s+=prefix+"  Arrays.asList(\n";
                for(long r2:n){
                    if(r2==0){
                        s+=prefix+"    "+"null,\n";
                    }else{
                        if((r2&1)==0){
                            s+=recursiveToString(r2>>>1, prefix+"    ", engine);
                        }else{
                            s+=prefix+"    "+"Array.asList(";
                            TreeMap m = new TreeMap();
                            HTreeMap.LinkedNode node =
                                    (HTreeMap.LinkedNode) engine.get
                                            (r2 >>> 1, serializer);
                            while(node!=null){
                                m.put(node.key, node.value);
                                node = (HTreeMap.LinkedNode) engine.get(node.next, serializer);
                            }
                            for(Object k:m.keySet()){
                                s+= k+","+m.get(k)+",";
                            }
                            //s=s.substring(0,s.length()-1);
                            s+="),\n";
                        }
                    }
                }
                s+=prefix+"  ),\n";
            }
        }
//            s=s.substring(0,s.length()-2);
            s+=prefix+"),\n";
        }
        return s;
    }


    @Test public void testDirSerializer() throws IOException {

        long[][] l = new long[16][];
        l[3] = new long[] {0,0,12,13,14,0,Long.MAX_VALUE,0};
        l[6] = new long[] {1,2,3,4,5,6,7,8};

        DataOutput2 out = new DataOutput2();
        HTreeMap.DIR_SERIALIZER.serialize(out,l);

        DataInput2 in = swap(out);

        long[][] b = HTreeMap.DIR_SERIALIZER.deserialize(in, -1);

        assertEquals(null, b[0]);
        assertEquals(null, b[1]);
        assertEquals(null, b[2]);
        assertEquals(Arrays.toString(new long[] {0,0,12,13,14,0,Long.MAX_VALUE,0}), Arrays.toString(b[3]));
        assertEquals(null, b[4]);
        assertEquals(null, b[5]);
        assertEquals(Arrays.toString(new long[] {1,2,3,4,5,6,7,8}), Arrays.toString(b[6]));
        assertEquals(null, b[7]);



    }

    DataInput2 swap(DataOutput2 d){
        byte[] b = d.copyBytes();
        return new DataInput2(ByteBuffer.wrap(b),0);
    }


    @Test public void ln_serialization() throws IOException {
        HTreeMap.LinkedNode n = new HTreeMap.LinkedNode(123456, 1111L, 123L, 456L);

        DataOutput2 out = new DataOutput2();

        serializer.serialize(out, n);

        DataInput2 in = swap(out);

        HTreeMap.LinkedNode n2  = (HTreeMap.LinkedNode) serializer.deserialize(in, -1);

        assertEquals(123456, n2.next);
        assertEquals(1111L, n2.expireLinkNodeRecid);
        assertEquals(123L,n2.key);
        assertEquals(456L,n2.value);
    }

    @Test public void test_simple_put(){

        HTreeMap m = new HTreeMap(engine,0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER, Serializer.BASIC_SERIALIZER,0,0,0,0,null,null);

        m.put(111L, 222L);
        m.put(333L, 444L);
        assertTrue(m.containsKey(111L));
        assertTrue(!m.containsKey(222L));
        assertTrue(m.containsKey(333L));
        assertTrue(!m.containsKey(444L));

        assertEquals(222L, m.get(111L));
        assertEquals(null, m.get(222L));
        assertEquals(444l, m.get(333L));
    }

    @Test public void test_hash_collision(){
        HTreeMap m = new HTreeMap(engine,0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER, Serializer.BASIC_SERIALIZER,0,0,0,0,null,null){
            @Override
            protected int hash(Object key) {
                return 0;
            }
        };

        for(long i = 0;i<20;i++){
            m.put(i,i+100);
        }

        for(long i = 0;i<20;i++){
            assertTrue(m.containsKey(i));
            assertEquals(i+100, m.get(i));
        }

        m.put(11L, 1111L);
        assertEquals(1111L,m.get(11L) );
    }

    @Test public void test_hash_dir_expand(){
        HTreeMap m = new HTreeMap(engine,0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER, Serializer.BASIC_SERIALIZER,0,0,0,0,null,null){
            @Override
            protected int hash(Object key) {
                return 0;
            }
        };

        for(long i = 0;i< HTreeMap.BUCKET_OVERFLOW;i++){
            m.put(i,i);
        }

        //segment should not be expanded
        long[][] l = engine.get(m.segmentRecids[0], HTreeMap.DIR_SERIALIZER);
        assertNotNull(l[0]);
        assertEquals(1, l[0][0]&1);  //last bite indicates leaf
        for(int j=1;j<8;j++){ //all others should be null
            assertEquals(0, l[0][j]);
        }
        long recid = l[0][0]>>>1;

        for(long i = HTreeMap.BUCKET_OVERFLOW -1; i>=0; i--){
            assertTrue(recid!=0);
            HTreeMap.LinkedNode  n = (HTreeMap.LinkedNode) engine.get(recid, m.LN_SERIALIZER);
            assertEquals(i, n.key);
            assertEquals(i, n.value);
            recid = n.next;
        }

        //adding one more item should trigger dir expansion to next level
        m.put((long) HTreeMap.BUCKET_OVERFLOW, (long) HTreeMap.BUCKET_OVERFLOW);

        recid = m.segmentRecids[0];

        l = engine.get(recid, HTreeMap.DIR_SERIALIZER);
        assertNotNull(l[0]);
        for(int j=1;j<8;j++){ //all others should be null
           assertEquals(null, l[j]);
        }

        assertEquals(0, l[0][0]&1); //last bite indicates leaf
        for(int j=1;j<8;j++){ //all others should be zero
          assertEquals(0, l[0][j]);
        }

        recid = l[0][0]>>>1;


        l = engine.get(recid, HTreeMap.DIR_SERIALIZER);
        assertNotNull(l[0]);
        for(int j=1;j<8;j++){ //all others should be null
            assertEquals(null, l[j]);
        }

        assertEquals(1, l[0][0]&1); //last bite indicates leaf
        for(int j=1;j<8;j++){ //all others should be zero
            assertEquals(0, l[0][j]);
        }

        recid = l[0][0]>>>1;

        for(long i = 0; i<= HTreeMap.BUCKET_OVERFLOW; i++){
            assertTrue(recid!=0);
            HTreeMap.LinkedNode n = (HTreeMap.LinkedNode) engine.get(recid, m.LN_SERIALIZER);

            assertNotNull(n);
            assertEquals(i, n.key);
            assertEquals(i, n.value);
            recid = n.next;
        }

    }


    @Test public void test_delete(){
        HTreeMap m = new HTreeMap(engine,0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER, Serializer.BASIC_SERIALIZER,0,0,0,0,null,null){
            @Override
            protected int hash(Object key) {
                return 0;
            }
        };

        for(long i = 0;i<20;i++){
            m.put(i,i+100);
        }

        for(long i = 0;i<20;i++){
            assertTrue(m.containsKey(i));
            assertEquals(i+100, m.get(i));
        }


        for(long i = 0;i<20;i++){
            m.remove(i);
        }

        for(long i = 0;i<20;i++){
            assertTrue(!m.containsKey(i));
            assertEquals(null, m.get(i));
        }
    }

    @Test public void clear(){
        HTreeMap m = new HTreeMap(engine,0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER, Serializer.BASIC_SERIALIZER,0,0,0,0,null,null);
        for(Integer i=0;i<100;i++){
            m.put(i,i);
        }
        m.clear();
        assertTrue(m.isEmpty());
        assertEquals(0, m.size());
    }

    @Test //(timeout = 10000)
     public void testIteration(){
        HTreeMap m = new HTreeMap(engine, 0,0,HTreeMap.preallocateSegments(engine),Serializer.BASIC_SERIALIZER,Serializer.BASIC_SERIALIZER,0,0,0,0,null,null){
            @Override
            protected int hash(Object key) {
                return (Integer) key;
            }
        };

        final int max = 140;
        final int inc = 111111;

        for(Integer i=0;i<max;i++){
            m.put(i,i+inc);
        }

        Iterator<Integer> keys = m.keySet().iterator();
        for(Integer i=0;i<max;i++){
            assertTrue(keys.hasNext());
            assertEquals(i, keys.next());
        }
        assertTrue(!keys.hasNext());

        Iterator<Integer> vals = m.values().iterator();
        for(Integer i=inc;i<max+inc;i++){
            assertTrue(vals.hasNext());
            assertEquals(i, vals.next());
        }
        assertTrue(!vals.hasNext());


        //great it worked, test stuff spread across segments
        m.clear();
        assertTrue(m.isEmpty());

        for(int i = 0;i<max;i++){
            m.put((1<<30)+i, i+inc);
            m.put((2<<30)+i, i+inc);
            m.put((3<<30)+i, i+inc);
        }

        assertEquals(max*3,m.size());

        int countSegments = 0;
        for(long segmentRecid:m.segmentRecids){
            long[][] segment = engine.get(segmentRecid, HTreeMap.DIR_SERIALIZER);
            for(long[] s:segment){
                if(s!=null){
                    countSegments++;
                    break;
                }
            }
        }

        assertEquals(3, countSegments);

        keys = m.keySet().iterator();
        for(int i=1;i<=3;i++){
            for(int j=0;j<max;j++){
                assertTrue(keys.hasNext());
                assertEquals(Integer.valueOf((i<<30)+j), keys.next());
            }
        }
        assertTrue(!keys.hasNext());

    }

    static final Long ZERO  = 0L;

    @Test public void expire_link_simple_add_remove(){
        HTreeMap m = db.createHashMap("test").expireMaxSize(100).make();
        m.segmentLocks[0].writeLock().lock();
        assertEquals(ZERO, engine.get(m.expireHeads[0], Serializer.LONG_SERIALIZER));
        assertEquals(ZERO, engine.get(m.expireTails[0], Serializer.LONG_SERIALIZER));

        m.expireLinkAdd(0, 111L,222);

        Long recid = engine.get(m.expireHeads[0], Serializer.LONG_SERIALIZER);
        assertNotEquals(ZERO,recid);
        assertEquals(recid, engine.get(m.expireTails[0], Serializer.LONG_SERIALIZER));

        HTreeMap.ExpireLinkNode n = engine.get(recid, HTreeMap.ExpireLinkNode.SERIALIZER);
        assertEquals(0, n.prev);
        assertEquals(0, n.next);
        assertEquals(111L, n.keyRecid);
        assertEquals(222, n.hash);

        assertArrayEquals(new int[]{222},getExpireList(m,0));

        n = m.expireLinkRemove(0);
        assertEquals(0, n.prev);
        assertEquals(0, n.next);
        assertEquals(111L, n.keyRecid);
        assertEquals(222L, n.hash);

        assertEquals(ZERO, engine.get(m.expireHeads[0], Serializer.LONG_SERIALIZER));
        assertEquals(ZERO, engine.get(m.expireTails[0], Serializer.LONG_SERIALIZER));
        assertArrayEquals(new int[]{},getExpireList(m,0));
    }

    @Test public void expire_link_test(){
        HTreeMap m = db.createHashMap("test").expireMaxSize(100).make();
        m.segmentLocks[2].writeLock().lock();

        long[] recids = new long[10];
        for(int i=1;i<10;i++){
            recids[i] = m.expireLinkAdd(2,i*10,i*100);
        }

        assertArrayEquals(new int[]{100,200,300,400,500,600,700,800,900},getExpireList(m,2));

        m.expireLinkBump(2,recids[8],0);
        assertArrayEquals(new int[]{100,200,300,400,500,600,700,900,800},getExpireList(m,2));

        m.expireLinkBump(2,recids[5],0);
        assertArrayEquals(new int[]{100,200,300,400,600,700,900,800,500},getExpireList(m,2));

        m.expireLinkBump(2,recids[1],0);
        assertArrayEquals(new int[]{200,300,400,600,700,900,800,500,100},getExpireList(m,2));

        assertEquals(200, m.expireLinkRemove(2).hash);
        assertArrayEquals(new int[]{300,400,600,700,900,800,500,100},getExpireList(m,2));

        assertEquals(300, m.expireLinkRemove(2).hash);
        assertArrayEquals(new int[]{400,600,700,900,800,500,100},getExpireList(m,2));



    }

    int[] getExpireList(HTreeMap m, int segment){
        int[] ret = new int[0];
        long recid = engine.get(m.expireTails[segment], Serializer.LONG_SERIALIZER);
        long prev = 0;
        //System.out.println("--");
        while(recid!=0){
            HTreeMap.ExpireLinkNode n = engine.get(recid, HTreeMap.ExpireLinkNode.SERIALIZER);
            //System.out.println(n.hash);
            assertEquals(prev,n.prev);
            prev = recid;
            recid = n.next;

            ret=Arrays.copyOf(ret,ret.length+1);
            ret[ret.length-1] = n.hash;

        }
        //System.out.println(Arrays.toString(ret));
        assertEquals(new Long(prev),engine.get(m.expireHeads[segment], Serializer.LONG_SERIALIZER));
        return ret;
    }


}

