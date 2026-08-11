import { afterEach, describe, expect, it, vi } from 'vitest';
import fileService from '../fileService';
import { getCloudFrontFileUrl } from '../../utils/fileUrl';

vi.mock('../../components/Toast', () => ({
  Toast: {
    error: vi.fn(),
  },
}));

describe('fileService', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('handles upload size limit errors without logging console errors', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});

    const result = fileService.handleUploadError(
      Object.assign(new Error('파일 크기는 5MB를 초과할 수 없습니다.'), {
        status: 413,
      })
    );

    expect(result).toEqual({
      success: false,
      message: '파일 크기는 5MB를 초과할 수 없습니다.',
    });
    expect(consoleError).not.toHaveBeenCalled();
  });

  it('builds CloudFront URLs for chat file reads', () => {
    expect(fileService.getFileUrl('sample image.png', true)).toBe(
      'https://d2nsun7j7a460i.cloudfront.net/chat/sample%20image.png'
    );
    expect(fileService.getPreviewUrl({ filename: 'sample.png' })).toBe(
      'https://d2nsun7j7a460i.cloudfront.net/chat/sample.png'
    );
  });

  it('keeps attachment downloads on the backend response path', () => {
    expect(fileService.getFileUrl('sample.png', false)).toBe('/api/files/download/sample.png');
  });

  it('maps legacy profile API paths to the profiles CloudFront key', () => {
    expect(getCloudFrontFileUrl('/api/files/profiles/avatar.png', 'profiles')).toBe(
      'https://d2nsun7j7a460i.cloudfront.net/profiles/avatar.png'
    );
  });
});
