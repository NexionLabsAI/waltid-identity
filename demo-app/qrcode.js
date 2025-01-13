function updateStatus(message, isError = false) {
    const statusDiv = document.getElementById('status');
    statusDiv.className = `status ${isError ? 'error' : 'success'}`;
    statusDiv.textContent = message;
}

function generateQRCode(url) {
    const qrContainer = document.getElementById('qr-container');
    qrContainer.innerHTML = ''; // Clear previous QR code

    new QRCode(qrContainer, {
        text: url,
        width: 256,
        height: 256
    });

    // Create copy button
    const copyButton = document.createElement('button');
    copyButton.textContent = 'Copy URL';
    copyButton.style.marginTop = '5px';
    copyButton.addEventListener('click', async () => {
        try {
            await navigator.clipboard.writeText(url);
            copyButton.textContent = 'Copied!';
            setTimeout(() => {
                copyButton.textContent = 'Copy URL';
            }, 2000);
        } catch (err) {
            console.error('Failed to copy:', err);
            copyButton.textContent = 'Copy failed';
        }
    });

    // Add elements to container
    qrContainer.appendChild(copyButton);
}

export { updateStatus, generateQRCode }; 