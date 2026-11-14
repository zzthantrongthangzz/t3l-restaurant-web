document.addEventListener("DOMContentLoaded", () => {
  const dialog = document.querySelector("#reservation-dialog");
  if (!dialog) return;

  const tableIdField = dialog.querySelector("[data-table-id-field]");
  const selectedName = dialog.querySelector("[data-selected-name]");
  const selectedDetails = dialog.querySelector("[data-selected-details]");
  const guestCount = dialog.querySelector("[data-guest-count]");
  const tableCards = [...document.querySelectorAll("[data-table-card]")];

  const updateSummary = (card) => {
    const capacity = Number(card.dataset.tableCapacity);
    selectedName.textContent = card.dataset.tableName;
    selectedDetails.textContent = `${card.dataset.tableZone} · ${card.dataset.tableType} · ${capacity} chỗ`;
    guestCount.max = String(capacity);
    if (!guestCount.value || Number(guestCount.value) > capacity) {
      guestCount.value = String(Math.min(2, capacity));
    }
  };

  tableCards.forEach((card) => {
    card.addEventListener("click", () => {
      if (card.disabled || card.dataset.available !== "true") return;
      tableIdField.value = card.dataset.tableId;
      updateSummary(card);
      dialog.showModal();
    });
  });

  dialog.querySelectorAll("[data-close-dialog]").forEach((button) => {
    button.addEventListener("click", () => dialog.close());
  });

  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });

  if (dialog.dataset.open === "true") {
    const selectedCard = tableCards.find((card) => card.dataset.tableId === tableIdField.value);
    if (selectedCard) updateSummary(selectedCard);
    dialog.showModal();
  }
});
