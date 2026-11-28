const publicScreens = [
  ['home', '/'], ['space', '/#space'], ['menu', '/menu'], ['about', '/about'], ['contact', '/contact'],
  ['faq', '/faq'], ['booking', '/reservations/new'], ['lookup', '/reservations/lookup']
];
const operations = [
  ['reservations', '/reservations'], ['new-reservation', '/reservations/new'],
  ['reservation-detail', '/reservations/{reservationId}'], ['tables', '/tables'],
  ['orders', '/orders'], ['order-detail', '/orders/{orderReservationId}'], ['shift', '/shift']
];

module.exports = {
  guest: [
    ...publicScreens, ['login', '/login'], ['register', '/register'], ['forgot-password', '/login/forgot-password'],
    ['legacy-recovery', '/login/recover'], ['staff-login', '/staff/login'],
    ['booking-unavailable', '/reservations/confirmation', 404], ['missing-booking-draft', '/reservations/new?draft=qa-missing', 200]
  ],
  buyer: [
    ...publicScreens, ['account', '/account'], ['reservations', '/account/reservations'],
    ['orders', '/account/orders'], ['vouchers', '/account/vouchers'], ['addresses', '/account/addresses'],
    ['new-address', '/account/addresses/new'], ['edit-address', '/account/addresses/{addressId}'],
    ['invoices', '/account/invoices'], ['invoice-company', '/account/invoices?tab=company'],
    ['preorder', '/account/reservations/{buyerCode}/preorder'],
    ['confirmation', '/reservations/confirmation?code={buyerCode}'], ['lookup-detail', '/reservations/lookup?code={buyerCode}'],
    ['restricted-staff', '/staff/dashboard', 403], ['missing-screen', '/qa-audit-not-a-route', 404]
  ],
  staff: [
    ['workspace', '/staff'], ['dashboard', '/staff/dashboard'],
    ...operations.map(([id, suffix]) => [id, '/staff' + suffix]), ['activity', '/staff/activity'],
    ['restricted-admin', '/admin/accounts', 403], ['restricted-buyer', '/account', 403]
  ],
  admin: [
    ['workspace', '/admin'], ['dashboard', '/admin/dashboard'],
    ...operations.map(([id, suffix]) => [id, '/admin' + suffix]),
    ['menu-admin', '/admin/dishes', 200, '/menu'], ['new-dish', '/admin/dishes/new'],
    ['edit-dish', '/admin/dishes/{dishId}/edit'], ['accounts', '/admin/accounts'],
    ['new-account', '/admin/accounts/new'], ['account-detail', '/admin/accounts/{accountId}'],
    ['inventory', '/admin/inventory'], ['new-table', '/admin/inventory/new'],
    ['edit-table', '/admin/inventory/{tableId}/edit'], ['categories', '/admin/categories'],
    ['vouchers', '/admin/vouchers'], ['new-voucher', '/admin/vouchers/new'],
    ['voucher-detail', '/admin/vouchers/{voucherId}'], ['faqs', '/admin/faqs'],
    ['new-faq', '/admin/faqs/new'], ['edit-faq', '/admin/faqs/{faqId}/edit'],
    ['contacts', '/admin/contacts'], ['contact-detail', '/admin/contacts/{contactId}'],
    ['settings', '/admin/settings'], ['invoices', '/admin/invoices'], ['invoice-detail', '/admin/invoices/{invoiceId}'],
    ['revenue', '/admin/revenue'], ['shifts', '/admin/shifts'], ['activity', '/admin/activity'],
    ['analytics', '/admin/analytics'], ['analytics-invalid-filter', '/admin/analytics?from=qa-invalid', 400],
    ['restricted-buyer', '/account', 403]
  ]
};
