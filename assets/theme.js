try {
  const mode = localStorage.getItem("scratch-theme");
  if (["system", "light", "dark"].includes(mode)) {
    document.documentElement.dataset.theme = mode;
  }
} catch (error) {
  console.warn("Theme preference cannot be read from this browser.", error);
}
