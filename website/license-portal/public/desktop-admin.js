import {wireAccountDeletion} from './admin-cleanup.js';
export async function mountDesktopAdmin(root,{call,post,escapeHtml:esc,humanDate:date,notice,onAccountDeleted=async()=>{}}) {
 const area=document.createElement('section');area.className='box';area.style.marginBottom='24px';
 area.innerHTML=`<h2>Đăng ký từ VNCode & thiết bị</h2><p>Khách nhập Gmail và bấm Đăng ký trong ứng dụng, chờ bạn duyệt và cấp khóa rồi nhập khóa license. Không cần OTP hoặc tài khoản website. Cấp khóa không tự kích hoạt.</p><p id="desktop-settings"></p><form id="desktop-filter" class="row"><input class="input" name="search" placeholder="Tìm Gmail" maxlength="254"><select class="input" name="status"><option value="pending">Chờ duyệt</option><option value="approved">Đã duyệt</option><option value="suspended">Bị khóa</option><option value="">Tất cả</option></select><button class="btn">Tìm / Làm mới</button></form><div id="desktop-users" class="admin-list"></div><div class="row"><button class="btn" id="desktop-prev">← Trước</button><span id="desktop-page"></span><button class="btn" id="desktop-next">Tiếp →</button></div><div id="desktop-devices"></div><div id="desktop-key" role="status"></div><details><summary>Nhật ký quản trị</summary><button class="btn" id="desktop-audit-refresh" type="button">Tải nhật ký</button><div id="desktop-audit"></div></details>`;
 root.insertBefore(area,root.children[1]);let page=0,total=0;
 const select=s=>area.querySelector(s);
 const diagnose=document.createElement('button');diagnose.type='button';diagnose.className='btn';diagnose.id='desktop-license-diagnose';diagnose.textContent='Kiểm tra cấu hình license';
 const diagnostic=document.createElement('div');diagnostic.id='desktop-license-diagnostics';diagnostic.setAttribute('role','status');
 select('#desktop-settings').after(diagnose,diagnostic);
 diagnose.onclick=async()=>{
  diagnose.disabled=true;
  try {
   const d=await call('/api/admin/desktop/diagnostics');
   const missing=d.schema.filter(row=>!row.ready).map(row=>row.table).join(', ');
   const next=d.activation_configuration_ready?'Cấu hình API sẵn sàng; vẫn cần thử kích hoạt license thật.':
    !d.key_id_matches?`Chưa đủ cấu hình: Key ID Worker cần là ${d.client_key_id||'ID của bản app mới'}.`:
    !d.signing_key_valid?`Chưa đủ cấu hình: Secret LICENSE_SIGNING_KEY cần chứa khóa riêng Ed25519 PKCS8 tương ứng ${d.client_key_id||'public key trong app'}.`:
    !d.signature_matches_client?'Chưa đủ cấu hình: Secret không khớp public key trong VNCode; đối chiếu cặp khóa đã tạo.':
    'Chưa đủ cấu hình: kiểm tra migration của các bảng/cột còn thiếu.';
   diagnostic.textContent=`Key ID Worker: ${d.key_id||'Chưa cấu hình'} · Key ID VNCode: ${d.client_key_id||'Chưa rõ'}${d.minimum_client_version?' · Cần VNCode '+d.minimum_client_version+' trở lên':''}. Khóa ký hợp lệ: ${d.signing_key_valid?'Có':'Không'} · Khớp khóa công khai VNCode: ${d.signature_matches_client&&d.key_id_matches?'Có':'Không'} · Database: ${d.database_ready?'Sẵn sàng':'Thiếu bảng/cột: '+missing}. ${next}`;
  }catch(error){diagnostic.textContent='Không kiểm tra được cấu hình license: '+error.message;}
  finally{diagnose.disabled=false;}
 };
 const texts={GMAIL_REQUIRED:'VNCode desktop cần địa chỉ @gmail.com hợp lệ.',EMAIL_NOT_VERIFIED:'Gmail chưa được xác minh.',ACCOUNT_SUSPENDED:'Tài khoản đang bị khóa.',REVOKE_DEVICES_BEFORE_REDUCING_LIMIT:'Thu hồi bớt thiết bị trước khi giảm giới hạn.',INVALID_POLICY:'Giới hạn không hợp lệ.'};
 async function settings(){const s=await call('/api/admin/desktop/settings');select('#desktop-settings').textContent=`Gửi email: ${s.mail_ready?'Đã cấu hình':'Chưa cấu hình'} · Ký license: ${s.signing_ready?'Đọc được khóa — cần đối chiếu VNCode':s.signing_key_present?'Có Secret nhưng khóa không hợp lệ':'Chưa cấu hình'} · Mặc định 1 máy, 1 cửa hàng, offline 72 giờ.`;}
 async function refresh(){
  const f=new FormData(select('#desktop-filter')),d=await call('/api/admin/desktop/requests?'+new URLSearchParams({page:String(page),status:f.get('status'),search:f.get('search')}));total=d.total;
  select('#desktop-page').textContent=`${total} khách · Trang ${page+1}`;select('#desktop-prev').disabled=page===0;select('#desktop-next').disabled=(page+1)*50>=total;
  select('#desktop-users').innerHTML=d.users.length?d.users.map(u=>`<article class="user-card"><strong>${esc(u.email)}</strong>${u.role==='admin'?' <span class="chip">Admin · license desktop riêng</span>':''} · ${esc(({pending:'Chờ duyệt',approved:'Đã duyệt',suspended:'Bị khóa'})[u.status])}<p class="small">Gmail ${u.email_verified?'đã xác minh email':'đã đăng ký; duyệt thủ công'} · Hạn: ${date(u.license_expires_at)} · ${u.device_count}/${u.max_devices} máy · ${u.max_shops} cửa hàng · Offline ${u.offline_hours} giờ</p><form data-user="${esc(u.id)}"><div class="row"><label>Ngày license <input class="input" name="days" type="number" min="1" max="3650" value="30" required></label><label>Số máy <input class="input" name="max_devices" type="number" min="1" max="10" value="${u.max_devices}" required></label><label>Cửa hàng <input class="input" name="max_shops" type="number" min="1" max="100" value="${u.max_shops}" required></label><label>Offline (giờ) <input class="input" name="offline_hours" type="number" min="0" max="168" value="${u.offline_hours}" required></label></div><p><label><input name="send_email" type="checkbox"> Gửi khóa qua Gmail sau khi cấp</label></p><div class="user-actions"><button class="btn btn-primary" name="action" value="approve-issue" ${u.status==='suspended'?'disabled':''}>${u.status==='pending'?'Duyệt & cấp khóa':'Cấp khóa mới / gia hạn'}</button><button class="btn" name="action" value="policy">Lưu giới hạn</button><button class="btn" type="button" data-devices="${esc(u.id)}">Thiết bị</button>${u.role==='admin'?'':`<button class="btn btn-danger" type="button" data-delete-user="${esc(u.id)}" data-email="${esc(u.email)}">Xóa Gmail</button>`}</div></form></article>`).join(''):'<p>Không có khách phù hợp.</p>';
  select('#desktop-users').querySelectorAll('form').forEach(form=>form.addEventListener('submit',async e=>{
   e.preventDefault();const action=e.submitter.getAttribute('value'),id=form.dataset.user,f=new FormData(form);
   const b={days:Number(f.get('days')),max_devices:Number(f.get('max_devices')),max_shops:Number(f.get('max_shops')),offline_hours:Number(f.get('offline_hours')),send_email:f.has('send_email')};
   if(!confirm(action==='approve-issue'?`Duyệt/cấp khóa ${b.days} ngày cho khách này? Thời hạn bắt đầu khi khách kích hoạt.`:'Lưu giới hạn mới? Quyền offline đã cấp vẫn còn hiệu lực đến hạn.'))return;
   e.submitter.disabled=true;
   try{const r=await post(`/api/admin/desktop/users/${id}/${action}`,b);
    if(r.code){const box=select('#desktop-key');box.replaceChildren();const title=document.createElement('h3');title.textContent='Khóa mới — sao chép và gửi riêng cho khách';const input=document.createElement('input');input.className='input';input.readOnly=true;input.value=r.code;input.autocomplete='off';const msg=document.createElement('p');msg.textContent=r.email_sent?'Đã gửi email. Khóa đầy đủ không được lưu trên server.':b.send_email?'Gửi email chưa thành công. Hãy sao chép khóa và gửi riêng cho khách.':'Hãy sao chép khóa và gửi riêng cho khách. Khóa đầy đủ chỉ hiện trong lần cấp này.';const clear=document.createElement('button');clear.className='btn';clear.textContent='Ẩn khóa';clear.onclick=()=>box.replaceChildren();box.append(title,input,msg,clear);}
    notice('Đã cập nhật');await refresh();
   }catch(err){notice(texts[err.message]||err.message,true);}finally{if(e.submitter)e.submitter.disabled=false;}
  }));
  select('#desktop-users').querySelectorAll('[data-devices]').forEach(btn=>btn.onclick=()=>devices(btn.dataset.devices).catch(e=>notice(e.message,true)));
  wireAccountDeletion(select('#desktop-users'),{post,notice,onDeleted:async()=>{
   select('#desktop-devices').replaceChildren();select('#desktop-key').replaceChildren();
   if(page>0&&d.users.length===1)page--;
   await refresh();await onAccountDeleted();
  }});
 }
 async function devices(id){const d=await call(`/api/admin/desktop/users/${id}/devices`);const box=select('#desktop-devices');box.innerHTML='<h3>Thiết bị của khách</h3><p>Thu hồi ngăn kiểm tra quyền online; máy offline có thể dùng đến hạn quyền đã cấp.</p>'+ (d.devices.length?d.devices.map(v=>`<article class="user-card"><strong>${esc(v.name)}</strong> · ${v.revoked?'Đã thu hồi':'Hoạt động'}<p>Lần kết nối: ${date(v.last_seen_at)} · Hạn offline cuối: ${date(v.last_lease_expires_at)}</p>${v.revoked?'':`<button class="btn btn-danger" data-revoke="${esc(v.id)}">Thu hồi thiết bị</button>`}</article>`).join(''):'<p>Chưa có thiết bị.</p>');box.querySelectorAll('[data-revoke]').forEach(btn=>btn.onclick=async()=>{if(!confirm('Thu hồi thiết bị này và giải phóng chỗ cho máy khác?'))return;btn.disabled=true;try{await post(`/api/admin/desktop/devices/${btn.dataset.revoke}/revoke`,{});await devices(id);await refresh();}catch(e){notice(e.message,true);}finally{btn.disabled=false;}});}
 select('#desktop-audit-refresh').onclick=async()=>{try{const d=await call('/api/admin/audit');select('#desktop-audit').innerHTML=d.events.length?d.events.map(e=>`<p><strong>${esc(e.action)}</strong> · ${date(e.created_at)}<br><span class="small">Đối tượng: ${esc(e.target_id||'')} · ${esc(e.detail||'')}</span></p>`).join(''):'<p>Chưa có lịch sử.</p>';}catch(e){select('#desktop-audit').textContent=e.message;}};
 select('#desktop-filter').onsubmit=e=>{e.preventDefault();page=0;refresh().catch(e=>notice(e.message,true));};
 select('#desktop-prev').onclick=()=>{page=Math.max(0,page-1);refresh().catch(e=>notice(e.message,true));};
 select('#desktop-next').onclick=()=>{if((page+1)*50<total)page++;refresh().catch(e=>notice(e.message,true));};
 try{await settings();await refresh();}catch(e){select('#desktop-users').textContent='Không tải được chức năng mới: '+e.message;}
 return {refresh,async focusAccount(email){
  const form=select('#desktop-filter');form.querySelector('[name="search"]').value=email;
  form.querySelector('[name="status"]').value='';page=0;await refresh();
  area.scrollIntoView?.({behavior:'smooth',block:'start'});
 }};
}
