class WalletClient {
    constructor(apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    async login(username, password) {
        const response = await fetch(`${this.apiBaseUrl}/wallet-api/auth/login`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ "type": "email", "email": username, "password": password })
        });

        if (!response.ok) {
            throw new Error('Login failed');
        }

        return response.json();
    }

    async getDids(token, walletId) {
        const response = await fetch(`${this.apiBaseUrl}/wallet-api/wallet/${walletId}/dids`, {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            method: 'GET'
        });

        if (!response.ok) {
            throw new Error('Failed to retrieve DIDs');
        }

        return response.json();
    }

    // Create and sign the Verifiable Presentation
    async createVerifiablePresentation(token, presentationDefinition, matchingCredentials, walletId, userDid){
        try {
            const response = await fetch(`https://wallet.walt.id/wallet-api/wallet/${walletId}/exchange/usePresentationRequest`, {
              method: 'POST',
              headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                did: userDid,
                presentation_definition: presentationDefinition,
                credentials: matchingCredentials,
              }),
            });
            return response.json();
          } catch (error) {
            console.error('Error creating Verifiable Presentation:', error);
            throw error;
          }
        }
    
    //get matching credentials for presentation definition
    async matchCredentials(token, walletId, presentationDefinition) {
        try {
          const response = await fetch(`${this.apiBaseUrl}/wallet-api/wallet/${walletId}/exchange/matchCredentialsForPresentationDefinition`, {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json'
            },
            method: 'POST',
            body: JSON.stringify(presentationDefinition)
        });
          return response.json();
        } catch (error) {
          console.error('Error matching credentials:', error);
          throw error;
        }
      }

      async submitVerifiablePresentation(verifiablePresentation, response_uri) {
        try {
          const response = await fetch(decodeURIComponent(response_uri), {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify(verifiablePresentation),
          });
          return response.json();
        } catch (error) {
          console.error('Error submitting Verifiable Presentation:', error);
          throw error;
        }
      }

    async getWallets(token) {
        const response = await fetch(`${this.apiBaseUrl}/wallet-api/wallet/accounts/wallets`, {
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });

        if (!response.ok) {
            throw new Error('Failed to retrieve wallets');
        }

        return response.json();
    }
}

export { WalletClient }; 