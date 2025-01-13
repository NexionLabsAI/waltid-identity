class VerificationClient {
    constructor(apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    async requestVerification() {
        try {
            const response = await fetch(`${this.apiBaseUrl}/openid4vc/verify`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    "request_credentials": [
                        { "vct": "VerifiableCredentialType", "format": "vc+sd-jwt" }
                    ],
                    "policies": {
                        "age_verification": {
                            "min_age": 18
                        }
                    }
                })
            });

            if (!response.ok) {
                throw new Error(`Failed to create verification request: ${response.statusText}`);
            }
            const verificationUrl = await response.text()
            const { requestId, presentationDefinitionUri, responseUri } = extractSessionId(verificationUrl);

            // Resolve the URL and get the result
            const presentation_response = await fetch(presentationDefinitionUri);
            if (!presentation_response.ok) {
                throw new Error('Network response was not ok');
            }

            // Convert result to text
            const resultText = await presentation_response.text();

            // Decode and parse resultText to an Object
            const presentationDefinition = JSON.parse(resultText);


            return { verificationUrl, requestId, presentationDefinition, responseUri};
        } catch (error) {
            console.error('Request creation error:', error);
            throw error;
        }
    }

    async checkVerificationStatus(requestId) {
        try {
            const response = await fetch(`${this.apiBaseUrl}/openid4vc/session/${requestId}`);
            if (!response.ok) {
                throw new Error(`Failed to check status: ${response.statusText}`);
            }
            return await response.json();
        } catch (error) {
            console.error('Status check error:', error);
            throw error;
        }
    }
}

// Function to extract the session_id (state parameter) from the URL
function extractSessionId(url) {
    try {
        // Replace the custom scheme with 'https' to create a valid URL object
        const normalizedUrl = url.replace('openid4vp://', 'https://');
        const parsedUrl = new URL(normalizedUrl);
        const params = new URLSearchParams(parsedUrl.search);
        return {
            requestId: params.get('state'),
            presentationDefinitionUri: params.get('presentation_definition_uri'),
            responseUri: params.get('response_uri')
        };
    } catch (error) {
        console.error('Invalid URL:', error);
        return null;
    }
}

export { VerificationClient }; 