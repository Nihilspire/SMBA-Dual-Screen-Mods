# usage: python3 tests/objlist.py  (run where sma.gba is; prints every object type with where it appears, writes objlists.json)
# objlist.py: every level's object lists from the ROM. Table 0x08364D84: 20 levels x 11 u32 pointers (index =
# level*11 + 030019FE). List = records (u16 type, u16 tile x, u16 tile y), ended by a single u16 0x00FF.
import struct,json,collections
rom=open('sma.gba','rb').read()
LV=['1-1','1-2','1-3','2-1','2-2','2-3','3-1','3-2','3-3','4-1','4-2','4-3','5-1','5-2','5-3','6-1','6-2','6-3','7-1','7-2']
out={}; tally=collections.Counter(); where=collections.defaultdict(list)
for L in range(20):
    for A in range(11):
        p=struct.unpack_from('<I',rom,0x364D84+4*(L*11+A))[0]
        if not p: continue
        o=p-0x08000000; recs=[]
        while True:
            t=struct.unpack_from('<H',rom,o)[0]
            if t==0xFF or len(recs)>200: break
            x,y=struct.unpack_from('<HH',rom,o+2); recs.append((t,x,y)); o+=6
        out['%d/%d'%(L,A)]=recs
        for t,x,y in recs: tally[t]+=1; where[t].append('%s#%d@%d,%d'%(LV[L],A,x,y))
json.dump(out,open('objlists.json','w'))
for t in sorted(tally): print('%02X %3d  %s'%(t,tally[t],' '.join(where[t][:6])))
