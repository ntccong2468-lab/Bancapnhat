#!/usr/bin/env python3
"""Read-only PE resource parser: locate RT_RCDATA/MSI and compare MSI bytes."""
import hashlib,json,pathlib,struct,sys
exe=pathlib.Path(sys.argv[1]);msi=pathlib.Path(sys.argv[2]);data=exe.read_bytes()
def need(ok,msg):
 if not ok:raise ValueError(msg)
def u16(off):need(0<=off<=len(data)-2,'u16 bounds');return struct.unpack_from('<H',data,off)[0]
def u32(off):need(0<=off<=len(data)-4,'u32 bounds');return struct.unpack_from('<I',data,off)[0]
need(data[:2]==b'MZ','MZ');pe=u32(0x3c);need(data[pe:pe+4]==b'PE\0\0','PE');coff=pe+4
need(u16(coff)==0x8664,'architecture');sections=u16(coff+2);optn=u16(coff+16);opt=coff+20;need(u16(opt)==0x20b,'PE32+')
need(u32(opt+108)>2,'resource directory missing');resrva=u32(opt+112+2*8);ressize=u32(opt+112+2*8+4);need(resrva and ressize,'resource RVA empty')
sec=opt+optn;table=[]
for i in range(sections):
 p=sec+i*40;need(p+40<=len(data),'section bounds');table.append((u32(p+12),u32(p+8),u32(p+20),u32(p+16)))
def offset(rva,length=1):
 for va,vs,raw,rs in table:
  if va<=rva and rva+length<=va+max(vs,rs) and rva-va+length<=rs:
   result=raw+rva-va;need(result+length<=len(data),'raw bounds');return result
 raise ValueError('RVA unmapped')
base=offset(resrva,16)
def scan(rel):
 need(0<=rel<ressize,'directory relative bounds');p=base+rel;need(p+16<=len(data),'directory header');count=u16(p+12)+u16(p+14);need(count<=10000 and p+16+count*8<=len(data),'directory entry bounds');out=[]
 for j in range(count):
  name=u32(p+16+j*8);value=u32(p+20+j*8)
  if name&0x80000000:
   off=base+(name&0x7fffffff);length=u16(off);need(off+2+length*2<=len(data),'name bounds');name=data[off+2:off+2+length*2].decode('utf-16le')
  out.append((name,value))
 return out
def select(entries,name,is_dir):
 found=[v for n,v in entries if n==name];need(len(found)==1,'missing or duplicate resource '+str(name));value=found[0];need(bool(value&0x80000000)==is_dir,'resource child type');return value&0x7fffffff
rt=select(scan(0),10,True);name=select(scan(rt),'MSI',True);langs=scan(name);need(len(langs)==1,'expected one MSI language');value=langs[0][1];need(not value&0x80000000,'invalid language entry');entry=base+(value&0x7fffffff);need(entry+16<=len(data),'data entry bounds');rva=u32(entry);length=u32(entry+4);pos=offset(rva,length);resource=memoryview(data)[pos:pos+length];h=hashlib.sha256(resource).hexdigest()
need(length==msi.stat().st_size,'MSI length mismatch')
with msi.open('rb') as f:
 hh=hashlib.file_digest(f,'sha256').hexdigest()
need(h==hh,'MSI hash mismatch');print(json.dumps({'result':'PASS','source':'independent-static-PE-resource-read','resourceType':10,'resourceName':'MSI','resourceBytes':length,'resourceSha256':h,'exeSha256':hashlib.sha256(data).hexdigest(),'msiSha256':hh,'executedInstaller':False},indent=2))
