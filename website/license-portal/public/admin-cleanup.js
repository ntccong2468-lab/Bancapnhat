const messages={
 EMAIL_CONFIRMATION_REQUIRED:'Nhập đúng Gmail để xác nhận xóa.',
 DELETE_CONFIRMATION_REQUIRED:'Cần xác nhận trước khi xóa.',
 ONLY_VOID_LICENSE_CAN_BE_DELETED:'Chỉ xóa được license đã hủy và chưa từng sử dụng.',
 ADMIN_ACCOUNT_CANNOT_BE_DELETED:'Không thể xóa tài khoản quản trị.',
 ACCOUNT_HAS_FOREIGN_LICENSE_HISTORY:'Tài khoản này còn lịch sử cấp license cho khách khác. Chưa thể xóa.',
 ACCOUNT_NOT_FOUND:'Tài khoản đã được xóa hoặc không còn tồn tại.',
 ACCOUNT_CHANGED:'Tài khoản đã thay đổi. Làm mới danh sách trước khi xóa.'
};
export function wireAccountDeletion(root,{post,notice,onDeleted}) {
 root.querySelectorAll('[data-delete-user]').forEach(button=>button.onclick=async()=>{
  const email=button.dataset.email;
  const typed=prompt(`Xóa Gmail ${email}, toàn bộ license và thiết bị của khách? Gmail sẽ đăng ký lại từ đầu và chờ duyệt. Quyền offline đã cấp chỉ hết hiệu lực khi đến hạn.\nNhập đúng Gmail để xác nhận:`);
  if(typed===null)return;
  if(typed.trim().toLowerCase()!==email.toLowerCase()){notice(messages.EMAIL_CONFIRMATION_REQUIRED,true);return;}
  button.disabled=true;
  try {
   await post(`/api/admin/users/${button.dataset.deleteUser}/delete`,{confirm_email:typed.trim()});
   notice('Đã xóa Gmail. Khách có thể đăng ký lại trong VNCode.');
   await onDeleted();
  }catch(error){notice(messages[error.message]||error.message,true);}
  finally{button.disabled=false;}
 });
}
export function wireVoidLicenseDeletion(root,{post,notice,onDeleted}) {
 root.querySelectorAll('[data-delete-license]').forEach(button=>button.onclick=async()=>{
  if(!confirm('Xóa license đã hủy khỏi danh sách? Không thể hoàn tác. Nhật ký quản trị được giữ lại.'))return;
  button.disabled=true;
  try {
   await post(`/api/admin/license-codes/${button.dataset.deleteLicense}/delete`,{confirm:true});
   notice('Đã xóa license đã hủy.');await onDeleted();
  }catch(error){notice(messages[error.message]||error.message,true);}
  finally{button.disabled=false;}
 });
}
