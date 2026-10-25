const sharp=require('sharp'); const path=require('path');
const RES='/root/Documents/deepseek-harness/default-workspace/mole-native/app/src/main/res/drawable-nodpi';
(async()=>{
  for(const c of ['grey','red','blue','green']){
    const f=path.join(RES,'basin_'+c+'.png');
    const t=await sharp(f).trim({threshold:1}).toBuffer({resolveWithObject:true});
    await sharp(t.data).png({compressionLevel:9}).toFile(f);
    console.log(c, t.info.width+'x'+t.info.height);
  }
})();
