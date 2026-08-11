import axios, { isCancel, CancelToken } from 'axios';
import axiosInstance from './axios';
import { Toast } from '../components/Toast';
import { getCloudFrontFileUrl } from '../utils/fileUrl';

class FileService {
  constructor() {
    this.baseUrl = process.env.NEXT_PUBLIC_API_URL;
    this.uploadLimit = 50 * 1024 * 1024; // 50MB
    this.retryAttempts = 3;
    this.retryDelay = 1000;
    this.activeUploads = new Map();

    this.allowedTypes = {
      image: {
        extensions: ['.jpg', '.jpeg', '.png', '.gif', '.webp'],
        mimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp'],
        maxSize: 10 * 1024 * 1024,
        name: '이미지'
      },
      document: {
        extensions: ['.pdf'],
        mimeTypes: ['application/pdf'],
        maxSize: 20 * 1024 * 1024,
        name: 'PDF 문서'
      }
    };
  }

  async validateFile(file) {
    if (!file) {
      const message = '파일이 선택되지 않았습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > this.uploadLimit) {
      const message = `파일 크기는 ${this.formatFileSize(this.uploadLimit)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    let isAllowedType = false;
    let maxTypeSize = 0;
    let typeConfig = null;

    for (const config of Object.values(this.allowedTypes)) {
      if (config.mimeTypes.includes(file.type)) {
        isAllowedType = true;
        maxTypeSize = config.maxSize;
        typeConfig = config;
        break;
      }
    }

    if (!isAllowedType) {
      const message = '지원하지 않는 파일 형식입니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > maxTypeSize) {
      const message = `${typeConfig.name} 파일은 ${this.formatFileSize(maxTypeSize)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    const ext = this.getFileExtension(file.name);
    if (!typeConfig.extensions.includes(ext.toLowerCase())) {
      const message = '파일 확장자가 올바르지 않습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    return { success: true };
  }

  async uploadFile(file, onProgress) {
    const validationResult = await this.validateFile(file);
    if (!validationResult.success) {
      return validationResult;
    }

    try {
      const source = CancelToken.source();
      this.activeUploads.set(file.name, source);

      const apiUrl = (path) => this.baseUrl ? `${this.baseUrl}${path}` : path;
      const presignResponse = await axiosInstance.post(apiUrl('/api/files/uploads/presign'), {
        originalname: file.name,
        mimetype: file.type,
        size: file.size
      }, {
        cancelToken: source.token
      });

      const upload = presignResponse.data?.upload;
      if (!presignResponse.data?.success || !upload?.url || !upload?.filename) {
        throw new Error(presignResponse.data?.message || '업로드 URL을 받지 못했습니다.');
      }

      await axios.put(upload.url, file, {
        headers: upload.headers,
        timeout: 30000,
        cancelToken: source.token,
        withCredentials: false,
        onUploadProgress: (progressEvent) => {
          if (onProgress) {
            const total = progressEvent.total || file.size;
            const percentCompleted = Math.round(
              (progressEvent.loaded * 100) / total
            );
            onProgress(percentCompleted);
          }
        }
      });

      const response = await axiosInstance.post(apiUrl('/api/files/uploads/complete'), {
        filename: upload.filename,
        originalname: file.name,
        mimetype: file.type,
        size: file.size
      }, {
        cancelToken: source.token
      });

      this.activeUploads.delete(file.name);

      if (!response.data || !response.data.success) {
        return {
          success: false,
          message: response.data?.message || '파일 업로드에 실패했습니다.'
        };
      }

      const fileData = response.data.file;
      return {
        success: true,
        data: {
          ...response.data,
          file: {
            ...fileData,
            url: this.getFileUrl(fileData.filename, true)
          }
        }
      };

    } catch (error) {
      this.activeUploads.delete(file.name);

      if (isCancel(error)) {
        return {
          success: false,
          message: '업로드가 취소되었습니다.'
        };
      }

      if (error.response?.status === 401) {
        throw new Error('Authentication expired. Please login again.');
      }

      return this.handleUploadError(error);
    }
  }
  getFileUrl(filename, forPreview = false) {
    if (!filename) return '';
    if (forPreview) return getCloudFrontFileUrl(filename, 'chat');

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    return `${baseUrl}/api/files/download/${filename}`;
  }

  getPreviewUrl(file) {
    if (!file?.filename) return '';
    return this.getFileUrl(file.filename, true);
  }

  getApplicationPreviewUrl(fileOrFilename) {
    const filename = typeof fileOrFilename === 'string'
      ? fileOrFilename
      : fileOrFilename?.filename;
    if (!filename) return '';

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    return `${baseUrl}/api/files/view/${encodeURIComponent(filename)}`;
  }

  getFileExtension(filename) {
    if (!filename) return '';
    const parts = filename.split('.');
    return parts.length > 1 ? `.${parts.pop().toLowerCase()}` : '';
  }

  formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`;
  }

  handleUploadError(error) {
    if (error.code === 'ECONNABORTED') {
      return {
        success: false,
        message: '파일 업로드 시간이 초과되었습니다.'
      };
    }

    const status = error.response?.status ?? error.status;
    const message = error.response?.data?.message ?? error.message;

    switch (status) {
      case 400:
        return {
          success: false,
          message: message || '잘못된 요청입니다.'
        };
      case 401:
        return {
          success: false,
          message: '인증이 필요합니다.'
        };
      case 413:
        return {
          success: false,
          message: message || '파일이 너무 큽니다.'
        };
      case 415:
        return {
          success: false,
          message: '지원하지 않는 파일 형식입니다.'
        };
      default:
        break;
    }

    console.error('Upload error:', error);

    if (axios.isAxiosError(error)) {
      switch (status) {
        case 500:
          return {
            success: false,
            message: '서버 오류가 발생했습니다.'
          };
        default:
          return {
            success: false,
            message: message || '파일 업로드에 실패했습니다.'
          };
      }
    }

    return {
      success: false,
      message: error.message || '알 수 없는 오류가 발생했습니다.',
      error
    };
  }

  cancelUpload(filename) {
    const source = this.activeUploads.get(filename);
    if (source) {
      source.cancel('Upload canceled by user');
      this.activeUploads.delete(filename);
      return {
        success: true,
        message: '업로드가 취소되었습니다.'
      };
    }
    return {
      success: false,
      message: '취소할 업로드를 찾을 수 없습니다.'
    };
  }

}

export default new FileService();
