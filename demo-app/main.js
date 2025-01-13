import { VerificationClient } from './VerificationClient.js';
import { WalletClient } from './WalletClient.js';
import { updateStatus, generateQRCode } from './qrcode.js';

const client = new VerificationClient('http://localhost:7003');

async function completeVerificationWithWallet(responseUri, presentationDefinition) {
    const walletClient = new WalletClient('http://localhost:7001');

    try {
        // Login to wallet
        const authResponse = await walletClient.login('bojan@miletic.cool', 'password123');
        const token = authResponse.token;

        // Get wallets
        const wallets = await walletClient.getWallets(token);
        const walletId = wallets.wallets[0].id;

        // Get DIDs
        const dids = await walletClient.getDids(token, walletId);

        //match credentials for presentation definition
        const matchingCredentials = await walletClient.matchCredentials(token, walletId, presentationDefinition);

        // onr onboarding did is created on account creation
        // we should use matchingCredentials here instead of did
        if (dids && dids.length > 0) {
            // Present credential using the first DID
            const verifiableCredential = await walletClient.createVerifiablePresentation(token, presentationDefinition, matchingCredentials, walletId, dids[0].did);
            await walletClient.submitVerifiablePresentation(verifiableCredential, responseUri);
            return;
        } else {
            throw new Error('No DIDs found in wallet');
        }
    } catch (error) {
        console.error('Wallet verification failed:', error);
        throw error;
    }
}

async function initiateAgeVerification() {
    try {
        updateStatus('Initiating verification...');
        const { verificationUrl, requestId, presentationDefinition, responseUri } = await client.requestVerification();
        generateQRCode(verificationUrl);

        //after showing QR code, we need to wait for the user to scan it and then we need to complete the verification with the wallet
        // or we can try to login user authomatically
        await completeVerificationWithWallet(responseUri, presentationDefinition);
        updateStatus('Verification request sent through wallet');

        // Continue with existing polling logic
        const pollInterval = setInterval(async () => {
            try {
                const status = await client.checkVerificationStatus(requestId);
                if (status.verificationResult === true) {
                    clearInterval(pollInterval);
                    if (status.result.isVerified) {
                        updateStatus('Age verification successful! Access granted.');
                    } else {
                        updateStatus('Age verification failed. Access denied.', true);
                    }
                }
            } catch (error) {
                clearInterval(pollInterval);
                updateStatus('Error checking verification status: ' + error.message, true);
            }
        }, 2000);
    } catch (error) {
        updateStatus('Verification process failed: ' + error.message, true);
    }
}

// Expose the function globally for the button click
window.initiateAgeVerification = initiateAgeVerification; 
