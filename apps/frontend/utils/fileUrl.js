const DEFAULT_CLOUDFRONT_URL = 'https://d2nsun7j7a460i.cloudfront.net';

const cloudFrontUrl = () => (
  process.env.NEXT_PUBLIC_CLOUDFRONT_URL || DEFAULT_CLOUDFRONT_URL
).replace(/\/+$/, '');

const encodeKey = (key) => key
  .split('/')
  .filter(Boolean)
  .map(encodeURIComponent)
  .join('/');

/**
 * S3 key 또는 기존 백엔드 상대 URL을 CloudFront URL로 변환한다.
 */
export const getCloudFrontFileUrl = (pathOrKey, defaultPrefix) => {
  if (!pathOrKey) return null;
  if (/^https?:\/\//i.test(pathOrKey)) return pathOrKey;

  let key = pathOrKey.replace(/^\/+/, '');
  if (key.startsWith('api/files/')) {
    key = key.substring('api/files/'.length);
  } else if (!key.startsWith('chat/') && !key.startsWith('profiles/')) {
    key = `${defaultPrefix}/${key}`;
  }

  return `${cloudFrontUrl()}/${encodeKey(key)}`;
};

