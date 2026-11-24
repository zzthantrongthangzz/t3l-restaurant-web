const adminMenu = document.querySelector('.admin-toggle');
if (adminMenu) {
  const navigation = document.getElementById(adminMenu.getAttribute('aria-controls'));
  const small = matchMedia('(max-width: 900px)');
  const update = (open) => {
    adminMenu.hidden = !small.matches;
    adminMenu.setAttribute('aria-expanded', String(small.matches && open));
    navigation.toggleAttribute('data-collapsed', small.matches && !open);
  };
  update(false);
  small.addEventListener('change', () => update(false));
  adminMenu.addEventListener('click', () => update(adminMenu.getAttribute('aria-expanded') !== 'true'));
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && adminMenu.getAttribute('aria-expanded') === 'true') {
      update(false);
      adminMenu.focus();
    }
  });
}

const revenueChart = document.querySelector('.admin-bars');
if (revenueChart) {
  // If the chart has to scroll, put the latest periods in view initially and after resizing.
  const latestPeriods = new ResizeObserver(() => {
    revenueChart.scrollLeft = Math.max(0, revenueChart.scrollWidth - revenueChart.clientWidth);
  });
  latestPeriods.observe(revenueChart);
}

const dishImage = document.getElementById('dish-editor-image');
if (dishImage) {
  const imagePreview = document.getElementById('dish-editor-preview');
  const original = imagePreview.src;
  let previewUrl;
  dishImage.addEventListener('change', () => {
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    const image = dishImage.files[0];
    previewUrl = image ? URL.createObjectURL(image) : null;
    imagePreview.src = previewUrl || original;
  });
}
