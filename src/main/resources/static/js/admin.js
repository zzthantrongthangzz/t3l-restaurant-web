const adminMenu = document.querySelector('.admin-toggle');
if (adminMenu) {
  const navigation = document.getElementById(adminMenu.getAttribute('aria-controls'));
  const small = matchMedia('(max-width: 900px)');
  const groups = [...navigation.querySelectorAll('.nav-group')];
  const closeGroups = () => groups.forEach(group => { group.open = false; });
  groups.forEach(group => group.addEventListener('toggle', () => {
    if (!small.matches && group.open) groups.filter(other => other !== group).forEach(other => { other.open = false; });
  }));
  const update = (open) => {
    adminMenu.hidden = !small.matches;
    adminMenu.setAttribute('aria-expanded', String(small.matches && open));
    navigation.toggleAttribute('data-collapsed', small.matches && !open);
  };
  update(false);
  small.addEventListener('change', () => {
    closeGroups();
    update(false);
  });
  adminMenu.addEventListener('click', () => update(adminMenu.getAttribute('aria-expanded') !== 'true'));
  document.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return;
    if (adminMenu.getAttribute('aria-expanded') === 'true') {
      update(false);
      adminMenu.focus();
    } else {
      const open = groups.find(group => group.open);
      if (open) {
        closeGroups();
        open.querySelector('summary').focus();
      }
    }
  });
  document.addEventListener('click', event => {
    if (!navigation.contains(event.target) && !small.matches) closeGroups();
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
